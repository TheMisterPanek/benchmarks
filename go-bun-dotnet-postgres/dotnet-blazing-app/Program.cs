using System.Buffers;
using System.Buffers.Text;
using System.Collections.Concurrent;
using System.Data;
using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Server.Kestrel.Transport.Sockets;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Npgsql;
using NpgsqlTypes;

var config = AppConfig.Load("config.yaml");
var tableName = config.DbTable;
foreach (var ch in tableName)
{
    if (!(char.IsAsciiLetterOrDigit(ch) || ch == '_'))
    {
        throw new InvalidOperationException($"Invalid table name: {tableName}");
    }
}

var appName = Environment.GetEnvironmentVariable("APP_NAME") ?? "dotnet10-blazing";
var connString =
    $"Host={config.DbHost};Port=5432;Username={config.DbUser};Password={config.DbPassword};" +
    $"Database={config.DbDatabase};Pooling=false;No Reset On Close=true;Enlist=false;Ssl Mode=Disable;" +
    "Read Buffer Size=8192;Write Buffer Size=8192;";

var insertSql =
    $"INSERT INTO {tableName} (name, address, phone, image, created_at, updated_at) " +
    "VALUES ($1, $2, $3, $4, $5, $6) RETURNING id;";

using var dbPool = new BlazingDbPool(connString, insertSql, config.DbMaxConnections, appName);

var kestrelOptions = new KestrelServerOptions
{
    AddServerHeader = false,
};
kestrelOptions.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(60);
kestrelOptions.Listen(IPAddress.Any, config.AppPort, static listenOptions =>
{
    listenOptions.Protocols = HttpProtocols.Http1;
});

var transportOptions = new SocketTransportOptions
{
    UnsafePreferInlineScheduling = Environment.GetEnvironmentVariable("INLINE_SCHEDULING") == "1",
    NoDelay = true,
};

var loggerFactory = NullLoggerFactory.Instance;
var transportFactory = new SocketTransportFactory(Options.Create(transportOptions), loggerFactory);
using var server = new KestrelServer(Options.Create(kestrelOptions), transportFactory, loggerFactory);

var application = new BlazingHttpApplication(dbPool);

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    cts.Cancel();
};
AppDomain.CurrentDomain.ProcessExit += (_, _) => cts.Cancel();

await server.StartAsync(application, cts.Token);
Console.WriteLine($"{appName} is listening on http://0.0.0.0:{config.AppPort} ...");

try
{
    await Task.Delay(Timeout.Infinite, cts.Token);
}
catch (OperationCanceledException)
{
}

await server.StopAsync(CancellationToken.None);

sealed class BlazingHttpApplication(BlazingDbPool dbPool) : IHttpApplication<IFeatureCollection>
{
    private static readonly string ContentTypeJson = "application/json";
    private static readonly string ContentTypePlain = "text/plain";
    private static readonly byte[] OkBytes = "OK"u8.ToArray();
    private static readonly byte[] NotFoundBytes = "Resource not found"u8.ToArray();
    private static readonly byte[] InvalidBodyBytes = "{\"message\":\"Invalid JSON body\"}"u8.ToArray();

    private static readonly JsonEncodedText PropId = JsonEncodedText.Encode("id"u8);
    private static readonly JsonEncodedText PropName = JsonEncodedText.Encode("name"u8);
    private static readonly JsonEncodedText PropAddress = JsonEncodedText.Encode("address"u8);
    private static readonly JsonEncodedText PropPhone = JsonEncodedText.Encode("phone"u8);
    private static readonly JsonEncodedText PropImage = JsonEncodedText.Encode("image"u8);
    private static readonly JsonEncodedText PropCreatedAt = JsonEncodedText.Encode("createdAt"u8);
    private static readonly JsonEncodedText PropUpdatedAt = JsonEncodedText.Encode("updatedAt"u8);

    [ThreadStatic]
    private static GetScratch? t_getScratch;

    public IFeatureCollection CreateContext(IFeatureCollection contextFeatures) => contextFeatures;

    public void DisposeContext(IFeatureCollection context, Exception? exception) { }

    public Task ProcessRequestAsync(IFeatureCollection features)
    {
        var req = features.Get<IHttpRequestFeature>()!;
        var res = features.Get<IHttpResponseFeature>()!;
        var resBody = features.Get<IHttpResponseBodyFeature>()!;

        var path = req.Path;
        if (string.Equals(path, "/api/users", StringComparison.Ordinal))
        {
            var method = req.Method;
            if (method.Length == 3 && method[0] == 'G')
            {
                HandleGetUsers(res, resBody);
                return Task.CompletedTask;
            }
            if (method.Length == 4 && method[0] == 'P')
            {
                return HandlePostUserAsync(features.Get<IRequestBodyPipeFeature>()!, res, resBody);
            }
        }
        else if (string.Equals(path, "/healthz", StringComparison.Ordinal))
        {
            WriteBytes(res, resBody, 200, ContentTypePlain, OkBytes);
            return Task.CompletedTask;
        }

        WriteBytes(res, resBody, 404, ContentTypePlain, NotFoundBytes);
        return Task.CompletedTask;
    }

    private static void HandleGetUsers(IHttpResponseFeature res, IHttpResponseBodyFeature resBody)
    {
        var scratch = t_getScratch ??= new GetScratch();
        var buf = scratch.Buffer;
        var writer = scratch.Writer;
        buf.ResetWrittenCount();
        writer.Reset(buf);

        var u1 = new StackUser(1, "David D. Patton"u8, "1670 Stiles Street"u8, "412-578-3857"u8, "user.png"u8);
        var u2 = new StackUser(2, "Gary E. Eaton"u8, "828 Collins Avenue"u8, "614-866-1660"u8, "user.png"u8);
        var u3 = new StackUser(3, "John J. Fox"u8, "1895 Columbia Mine Road"u8, "304-505-3622"u8, "user.png"u8);

        writer.WriteStartArray();
        WriteUser(writer, u1);
        WriteUser(writer, u2);
        WriteUser(writer, u3);
        writer.WriteEndArray();
        writer.Flush();

        WriteBytes(res, resBody, 200, ContentTypeJson, buf.WrittenSpan);
    }

    private static void WriteUser(Utf8JsonWriter writer, in StackUser u)
    {
        writer.WriteStartObject();
        writer.WriteNumber(PropId, u.Id);
        writer.WriteString(PropName, u.Name);
        writer.WriteString(PropAddress, u.Address);
        writer.WriteString(PropPhone, u.Phone);
        writer.WriteString(PropImage, u.Image);
        writer.WriteEndObject();
    }

    private async Task HandlePostUserAsync(
        IRequestBodyPipeFeature reqBody,
        IHttpResponseFeature res,
        IHttpResponseBodyFeature resBody)
    {
        var slot = await dbPool.RentAsync();
        var broken = false;
        try
        {
            var reader = reqBody.Reader;
            int bodyLen = 0;

            while (true)
            {
                var readResult = await reader.ReadAsync();
                var sequence = readResult.Buffer;
                if (readResult.IsCompleted)
                {
                    bodyLen = (int)sequence.Length;
                    if (bodyLen == 0 || bodyLen > slot.RequestBuf.Length)
                    {
                        reader.AdvanceTo(sequence.End);
                        WriteBytes(res, resBody, 400, ContentTypeJson, InvalidBodyBytes);
                        return;
                    }

                    if (sequence.IsSingleSegment)
                    {
                        if (!TryParseUser(sequence.FirstSpan, slot))
                        {
                            reader.AdvanceTo(sequence.End);
                            WriteBytes(res, resBody, 400, ContentTypeJson, InvalidBodyBytes);
                            return;
                        }
                    }
                    else
                    {
                        sequence.CopyTo(slot.RequestBuf);
                        if (!TryParseUser(slot.RequestBuf.AsSpan(0, bodyLen), slot))
                        {
                            reader.AdvanceTo(sequence.End);
                            WriteBytes(res, resBody, 400, ContentTypeJson, InvalidBodyBytes);
                            return;
                        }
                    }

                    reader.AdvanceTo(sequence.End);
                    break;
                }

                reader.AdvanceTo(sequence.Start, sequence.End);
            }

            var now = DateTime.UtcNow;
            long epochMs = (now.Ticks - DateTime.UnixEpoch.Ticks) / TimeSpan.TicksPerMillisecond;
            int imageLen = slot.FormatImage(epochMs);

            slot.PName.TypedValue = new ReadOnlyMemory<byte>(slot.NameBuf, 0, slot.NameLen);
            slot.PAddress.TypedValue = new ReadOnlyMemory<byte>(slot.AddressBuf, 0, slot.AddressLen);
            slot.PPhone.TypedValue = new ReadOnlyMemory<byte>(slot.PhoneBuf, 0, slot.PhoneLen);
            slot.PImage.TypedValue = new ReadOnlyMemory<byte>(slot.ImageBuf, 0, imageLen);
            slot.PCreatedAt.TypedValue = now;
            slot.PUpdatedAt.TypedValue = now;

            int id;
            await using (var dbReader = await slot.Command.ExecuteReaderAsync(
                             CommandBehavior.SingleRow | CommandBehavior.SequentialAccess))
            {
                await dbReader.ReadAsync();
                id = dbReader.GetInt32(0);
            }

            var respBuf = slot.ResponseBuf;
            var jw = slot.JsonWriter;
            respBuf.ResetWrittenCount();
            jw.Reset(respBuf);

            jw.WriteStartObject();
            jw.WriteNumber(PropId, id);
            jw.WriteString(PropName, slot.NameBuf.AsSpan(0, slot.NameLen));
            jw.WriteString(PropAddress, slot.AddressBuf.AsSpan(0, slot.AddressLen));
            jw.WriteString(PropPhone, slot.PhoneBuf.AsSpan(0, slot.PhoneLen));
            jw.WriteString(PropImage, slot.ImageBuf.AsSpan(0, imageLen));
            jw.WriteString(PropCreatedAt, now);
            jw.WriteString(PropUpdatedAt, now);
            jw.WriteEndObject();
            jw.Flush();

            WriteBytes(res, resBody, 201, ContentTypeJson, respBuf.WrittenSpan);
        }
        catch (Exception ex)
        {
            broken = true;
            Console.Error.WriteLine(ex);
            var errBytes = Encoding.UTF8.GetBytes($"{{\"message\":\"{ex.Message.Replace("\"", "'")}\"}}");
            WriteBytes(res, resBody, 500, ContentTypeJson, errBytes);
        }
        finally
        {
            dbPool.Return(slot, broken);
        }
    }

    private static bool TryParseUser(ReadOnlySpan<byte> json, PooledDbSlot slot)
    {
        slot.NameLen = -1;
        slot.AddressLen = 0;
        slot.PhoneLen = 0;

        var jr = new Utf8JsonReader(json);
        if (!jr.Read() || jr.TokenType != JsonTokenType.StartObject)
        {
            return false;
        }

        while (jr.Read())
        {
            if (jr.TokenType == JsonTokenType.EndObject)
            {
                return slot.NameLen >= 0;
            }

            if (jr.TokenType != JsonTokenType.PropertyName)
            {
                return false;
            }

            var prop = jr.ValueSpan;
            if (!jr.Read())
            {
                return false;
            }

            if (jr.TokenType != JsonTokenType.String)
            {
                continue;
            }

            if (prop.SequenceEqual("name"u8))
            {
                slot.NameLen = CopyJsonString(ref jr, slot.NameBuf);
            }
            else if (prop.SequenceEqual("address"u8))
            {
                slot.AddressLen = CopyJsonString(ref jr, slot.AddressBuf);
            }
            else if (prop.SequenceEqual("phone"u8))
            {
                slot.PhoneLen = CopyJsonString(ref jr, slot.PhoneBuf);
            }
        }

        return slot.NameLen >= 0;
    }

    private static int CopyJsonString(ref Utf8JsonReader jr, byte[] dest)
    {
        if (!jr.ValueIsEscaped && !jr.HasValueSequence)
        {
            var span = jr.ValueSpan;
            span.CopyTo(dest);
            return span.Length;
        }

        return jr.CopyString(dest);
    }

    private static void WriteBytes(
        IHttpResponseFeature res,
        IHttpResponseBodyFeature resBody,
        int statusCode,
        string contentType,
        ReadOnlySpan<byte> body)
    {
        res.StatusCode = statusCode;
        var headers = res.Headers;
        headers.ContentType = contentType;
        headers.ContentLength = body.Length;

        var pipeWriter = resBody.Writer;
        var dest = pipeWriter.GetSpan(body.Length);
        body.CopyTo(dest);
        pipeWriter.Advance(body.Length);
    }

    private readonly ref struct StackUser(
        int id,
        ReadOnlySpan<byte> name,
        ReadOnlySpan<byte> address,
        ReadOnlySpan<byte> phone,
        ReadOnlySpan<byte> image)
    {
        public readonly int Id = id;
        public readonly ReadOnlySpan<byte> Name = name;
        public readonly ReadOnlySpan<byte> Address = address;
        public readonly ReadOnlySpan<byte> Phone = phone;
        public readonly ReadOnlySpan<byte> Image = image;
    }

    private sealed class GetScratch
    {
        public readonly ArrayBufferWriter<byte> Buffer = new(512);
        public readonly Utf8JsonWriter Writer;

        public GetScratch()
        {
            Writer = new Utf8JsonWriter(Buffer, new JsonWriterOptions { SkipValidation = true });
        }
    }
}

sealed class BlazingDbPool : IDisposable
{
    private readonly string _connString;
    private readonly string _insertSql;
    private readonly byte[] _imagePrefix;
    private readonly ConcurrentStack<PooledDbSlot> _stack = new();
    private readonly SemaphoreSlim _gate;

    public BlazingDbPool(string connString, string insertSql, int maxConnections, string appName)
    {
        _connString = connString;
        _insertSql = insertSql;
        _imagePrefix = Encoding.UTF8.GetBytes($"user-{appName}-");
        _gate = new SemaphoreSlim(maxConnections, maxConnections);
    }

    public ValueTask<PooledDbSlot> RentAsync()
    {
        if (_stack.TryPop(out var slot))
        {
            return ValueTask.FromResult(slot);
        }

        return RentSlowAsync();
    }

    private async ValueTask<PooledDbSlot> RentSlowAsync()
    {
        await _gate.WaitAsync();
        if (_stack.TryPop(out var existing))
        {
            _gate.Release();
            return existing;
        }

        try
        {
            return await PooledDbSlot.CreateAsync(_connString, _insertSql, _imagePrefix);
        }
        catch
        {
            _gate.Release();
            throw;
        }
    }

    public void Return(PooledDbSlot slot, bool broken)
    {
        if (broken || slot.Connection.FullState != ConnectionState.Open)
        {
            slot.Dispose();
            _gate.Release();
            return;
        }

        _stack.Push(slot);
    }

    public void Dispose()
    {
        while (_stack.TryPop(out var slot))
        {
            slot.Dispose();
        }
        _gate.Dispose();
    }
}

sealed class PooledDbSlot : IDisposable
{
    private static readonly byte[] ImageSuffix = ".png"u8.ToArray();

    public readonly NpgsqlConnection Connection;
    public readonly NpgsqlCommand Command;
    public readonly NpgsqlParameter<ReadOnlyMemory<byte>> PName;
    public readonly NpgsqlParameter<ReadOnlyMemory<byte>> PAddress;
    public readonly NpgsqlParameter<ReadOnlyMemory<byte>> PPhone;
    public readonly NpgsqlParameter<ReadOnlyMemory<byte>> PImage;
    public readonly NpgsqlParameter<DateTime> PCreatedAt;
    public readonly NpgsqlParameter<DateTime> PUpdatedAt;

    public readonly byte[] RequestBuf = new byte[4096];
    public readonly byte[] NameBuf = new byte[512];
    public readonly byte[] AddressBuf = new byte[512];
    public readonly byte[] PhoneBuf = new byte[64];
    public readonly byte[] ImageBuf = new byte[128];
    public int NameLen;
    public int AddressLen;
    public int PhoneLen;

    public readonly ArrayBufferWriter<byte> ResponseBuf = new(1024);
    public readonly Utf8JsonWriter JsonWriter;

    private readonly int _prefixLen;

    private PooledDbSlot(NpgsqlConnection conn, NpgsqlCommand cmd, byte[] imagePrefix)
    {
        Connection = conn;
        Command = cmd;
        PName = (NpgsqlParameter<ReadOnlyMemory<byte>>)cmd.Parameters[0];
        PAddress = (NpgsqlParameter<ReadOnlyMemory<byte>>)cmd.Parameters[1];
        PPhone = (NpgsqlParameter<ReadOnlyMemory<byte>>)cmd.Parameters[2];
        PImage = (NpgsqlParameter<ReadOnlyMemory<byte>>)cmd.Parameters[3];
        PCreatedAt = (NpgsqlParameter<DateTime>)cmd.Parameters[4];
        PUpdatedAt = (NpgsqlParameter<DateTime>)cmd.Parameters[5];

        imagePrefix.CopyTo(ImageBuf, 0);
        _prefixLen = imagePrefix.Length;

        JsonWriter = new Utf8JsonWriter(ResponseBuf, new JsonWriterOptions { SkipValidation = true });
    }

    public static async Task<PooledDbSlot> CreateAsync(string connString, string insertSql, byte[] imagePrefix)
    {
        var conn = new NpgsqlConnection(connString);
        await conn.OpenAsync();
        var cmd = new NpgsqlCommand(insertSql, conn);
        cmd.Parameters.Add(new NpgsqlParameter<ReadOnlyMemory<byte>> { NpgsqlDbType = NpgsqlDbType.Varchar });
        cmd.Parameters.Add(new NpgsqlParameter<ReadOnlyMemory<byte>> { NpgsqlDbType = NpgsqlDbType.Varchar });
        cmd.Parameters.Add(new NpgsqlParameter<ReadOnlyMemory<byte>> { NpgsqlDbType = NpgsqlDbType.Varchar });
        cmd.Parameters.Add(new NpgsqlParameter<ReadOnlyMemory<byte>> { NpgsqlDbType = NpgsqlDbType.Varchar });
        cmd.Parameters.Add(new NpgsqlParameter<DateTime> { NpgsqlDbType = NpgsqlDbType.TimestampTz });
        cmd.Parameters.Add(new NpgsqlParameter<DateTime> { NpgsqlDbType = NpgsqlDbType.TimestampTz });
        await cmd.PrepareAsync();

        return new PooledDbSlot(conn, cmd, imagePrefix);
    }

    public int FormatImage(long epochMs)
    {
        Utf8Formatter.TryFormat(epochMs, ImageBuf.AsSpan(_prefixLen), out int numBytes);
        int pos = _prefixLen + numBytes;
        ImageSuffix.CopyTo(ImageBuf.AsSpan(pos));
        return pos + ImageSuffix.Length;
    }

    public void Dispose()
    {
        JsonWriter.Dispose();
        Command.Dispose();
        Connection.Dispose();
    }
}

sealed class AppConfig
{
    public int AppPort { get; set; } = 8080;
    public string DbUser { get; set; } = "dotnet10_blazing_app";
    public string DbPassword { get; set; } = "devops123";
    public string DbHost { get; set; } = "localhost";
    public string DbDatabase { get; set; } = "store";
    public int DbMaxConnections { get; set; } = 100;
    public string DbTable { get; set; } = "dotnet10_blazing_app";

    public static AppConfig Load(string path)
    {
        var cfg = new AppConfig();
        var tableExplicitlySet = false;
        if (!File.Exists(path))
        {
            return cfg;
        }

        foreach (var rawLine in File.ReadAllLines(path))
        {
            var line = rawLine.Trim();
            if (string.IsNullOrEmpty(line) || line.StartsWith('#') || line == "---" || line == "db:")
            {
                continue;
            }

            var sepIdx = line.IndexOf(':');
            if (sepIdx <= 0)
            {
                continue;
            }

            var key = line[..sepIdx].Trim();
            var value = line[(sepIdx + 1)..].Trim().Trim('"', '\'');

            switch (key)
            {
                case "appPort":
                    if (int.TryParse(value, out var port)) cfg.AppPort = port;
                    break;
                case "user":
                    cfg.DbUser = value;
                    if (!tableExplicitlySet) cfg.DbTable = value;
                    break;
                case "password":
                    cfg.DbPassword = value;
                    break;
                case "host":
                    cfg.DbHost = value;
                    break;
                case "database":
                    cfg.DbDatabase = value;
                    break;
                case "maxConnections":
                    if (int.TryParse(value, out var maxConn)) cfg.DbMaxConnections = maxConn;
                    break;
                case "table":
                    cfg.DbTable = value;
                    tableExplicitlySet = true;
                    break;
            }
        }

        return cfg;
    }
}
