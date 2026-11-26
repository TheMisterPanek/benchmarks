using System.Buffers;
using System.Data;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;
using Npgsql;

var config = AppConfig.Load("config.yaml");
var tableName = config.DbTable;
foreach (var ch in tableName)
{
    if (!(char.IsAsciiLetterOrDigit(ch) || ch == '_'))
    {
        throw new InvalidOperationException($"Invalid table name: {tableName}");
    }
}

var appName = Environment.GetEnvironmentVariable("APP_NAME") ?? tableName;

var builder = WebApplication.CreateSlimBuilder(args);
builder.Logging.ClearProviders();
builder.WebHost.UseUrls($"http://0.0.0.0:{config.AppPort}");
builder.WebHost.ConfigureKestrel(options =>
{
    options.AddServerHeader = false;
    options.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(60);
});

var connString =
    $"Host={config.DbHost};Port=5432;Username={config.DbUser};Password={config.DbPassword};" +
    $"Database={config.DbDatabase};Maximum Pool Size={config.DbMaxConnections};" +
    "Max Auto Prepare=20;Auto Prepare Min Usages=2;No Reset On Close=true;Enlist=false;Ssl Mode=Disable;";
await using var dataSource = NpgsqlDataSource.Create(connString);

var insertSql =
    $"INSERT INTO {tableName} (name, address, phone, image, created_at, updated_at) " +
    "VALUES ($1, $2, $3, $4, $5, $6) RETURNING id;";

var app = builder.Build();

app.MapGet("/healthz", static (HttpContext context) =>
{
    context.Response.StatusCode = StatusCodes.Status200OK;
    return context.Response.WriteAsync("OK");
});

app.MapGet("/api/users", static (HttpContext context) =>
{
    User[] users =
    [
        new(1, "David D. Patton", "1670 Stiles Street", "412-578-3857", "user.png", null, null),
        new(2, "Gary E. Eaton", "828 Collins Avenue", "614-866-1660", "user.png", null, null),
        new(3, "John J. Fox", "1895 Columbia Mine Road", "304-505-3622", "user.png", null, null),
    ];

    context.Response.StatusCode = StatusCodes.Status200OK;
    context.Response.ContentType = "application/json; charset=utf-8";
    JsonWriterHelper.Write(context.Response.BodyWriter, users, AppJsonSerializerContext.Default.UserArray);
    return Task.CompletedTask;
});

app.MapPost("/api/users", async (HttpContext context) =>
{
    var ct = context.RequestAborted;
    try
    {
#if NET10_0_OR_GREATER
        var incomingUser = await JsonSerializer.DeserializeAsync(
            context.Request.BodyReader,
            AppJsonSerializerContext.Default.CreateUserRequest,
            ct);
#else
        var incomingUser = await JsonSerializer.DeserializeAsync(
            context.Request.Body,
            AppJsonSerializerContext.Default.CreateUserRequest,
            ct);
#endif

        if (incomingUser.Name is null)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            context.Response.ContentType = "application/json; charset=utf-8";
            JsonWriterHelper.Write(
                context.Response.BodyWriter,
                new ErrorResponse("Invalid JSON body"),
                AppJsonSerializerContext.Default.ErrorResponse);
            return;
        }

        var now = DateTime.UtcNow;
        var epochMs = new DateTimeOffset(now).ToUnixTimeMilliseconds();
        var key = $"user-{appName}-{epochMs}.png";

        await using var cmd = dataSource.CreateCommand(insertSql);
        cmd.Parameters.Add(new NpgsqlParameter<string> { TypedValue = incomingUser.Name });
        cmd.Parameters.Add(new NpgsqlParameter<string> { TypedValue = incomingUser.Address });
        cmd.Parameters.Add(new NpgsqlParameter<string> { TypedValue = incomingUser.Phone });
        cmd.Parameters.Add(new NpgsqlParameter<string> { TypedValue = key });
        cmd.Parameters.Add(new NpgsqlParameter<DateTime> { TypedValue = now });
        cmd.Parameters.Add(new NpgsqlParameter<DateTime> { TypedValue = now });

        int id;
        await using (var reader = await cmd.ExecuteReaderAsync(
                         CommandBehavior.SingleRow | CommandBehavior.SequentialAccess, ct))
        {
            await reader.ReadAsync(ct);
            id = reader.GetInt32(0);
        }

        var user = new User(
            Id: id,
            Name: incomingUser.Name,
            Address: incomingUser.Address,
            Phone: incomingUser.Phone,
            Image: key,
            CreatedAt: now,
            UpdatedAt: now);

        context.Response.StatusCode = StatusCodes.Status201Created;
        context.Response.ContentType = "application/json; charset=utf-8";
        JsonWriterHelper.Write(context.Response.BodyWriter, user, AppJsonSerializerContext.Default.User);
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine(ex);
        context.Response.StatusCode = StatusCodes.Status400BadRequest;
        context.Response.ContentType = "application/json; charset=utf-8";
        JsonWriterHelper.Write(
            context.Response.BodyWriter,
            new ErrorResponse(ex.Message),
            AppJsonSerializerContext.Default.ErrorResponse);
    }
});

app.MapFallback(static (HttpContext context) =>
{
    context.Response.StatusCode = StatusCodes.Status404NotFound;
    return context.Response.WriteAsync("Resource not found");
});

Console.WriteLine($"{appName} is listening on http://0.0.0.0:{config.AppPort} ...");
app.Run();

static class JsonWriterHelper
{
    [ThreadStatic]
    private static Utf8JsonWriter? _writer;

    public static void Write<T>(IBufferWriter<byte> bufferWriter, T value, JsonTypeInfo<T> typeInfo)
    {
        var writer = _writer;
        if (writer is null)
        {
            writer = new Utf8JsonWriter(bufferWriter, new JsonWriterOptions { SkipValidation = true });
            _writer = writer;
        }
        else
        {
            writer.Reset(bufferWriter);
        }

        JsonSerializer.Serialize(writer, value, typeInfo);
        writer.Flush();
        writer.Reset();
    }
}

readonly record struct CreateUserRequest(
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("address")] string Address,
    [property: JsonPropertyName("phone")] string Phone
);

readonly record struct User(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("address")] string Address,
    [property: JsonPropertyName("phone")] string Phone,
    [property: JsonPropertyName("image")] string Image,
    [property: JsonPropertyName("createdAt"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] DateTime? CreatedAt,
    [property: JsonPropertyName("updatedAt"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] DateTime? UpdatedAt
);

readonly record struct ErrorResponse(
    [property: JsonPropertyName("message")] string Message
);

[JsonSerializable(typeof(CreateUserRequest))]
[JsonSerializable(typeof(User))]
[JsonSerializable(typeof(User[]))]
[JsonSerializable(typeof(ErrorResponse))]
internal partial class AppJsonSerializerContext : JsonSerializerContext;

sealed class AppConfig
{
    public int AppPort { get; set; } = 8080;
    public string DbUser { get; set; } = "dotnet10_jit_app";
    public string DbPassword { get; set; } = "devops123";
    public string DbHost { get; set; } = "localhost";
    public string DbDatabase { get; set; } = "store";
    public int DbMaxConnections { get; set; } = 100;
    public string DbTable { get; set; } = "dotnet10_jit_app";

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
