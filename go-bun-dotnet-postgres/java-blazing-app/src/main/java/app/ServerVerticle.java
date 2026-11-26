package app;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class ServerVerticle extends VerticleBase {

    private static final CharSequence HEADER_CONTENT_TYPE = HttpHeaders.CONTENT_TYPE;
    private static final CharSequence MEDIA_JSON = HttpHeaders.createOptimized("application/json");
    private static final Buffer OK_BODY = Buffer.buffer("OK");
    private static final Buffer INVALID_JSON_BODY = JsonCodec.encodeError("Invalid JSON body");
    private static final Buffer NOT_FOUND_BODY = Buffer.buffer("Resource not found");

    private final AppConfig cfg;
    private final String tableName;
    private final String imagePrefix;
    private final int poolSizePerVerticle;

    private Pool pool;
    private PreparedQuery<RowSet<Row>> insertQuery;
    private HttpServer server;

    public ServerVerticle(AppConfig cfg, String tableName, String imagePrefix, int poolSizePerVerticle) {
        this.cfg = cfg;
        this.tableName = tableName;
        this.imagePrefix = imagePrefix;
        this.poolSizePerVerticle = poolSizePerVerticle;
    }

    @Override
    public Future<?> start() {
        boolean nativeTransport = vertx.isNativeTransportEnabled();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(cfg.dbHost)
            .setPort(5432)
            .setDatabase(cfg.dbDatabase)
            .setUser(cfg.dbUser)
            .setPassword(cfg.dbPassword)
            .setSslMode(SslMode.DISABLE)
            .setCachePreparedStatements(true)
            .setPreparedStatementCacheMaxSize(32)
            .setPipeliningLimit(256);

        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(poolSizePerVerticle)
            .setShared(false);

        this.pool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        String insertSql = "INSERT INTO " + tableName
            + " (name, address, phone, image, created_at, updated_at)"
            + " VALUES ($1, $2, $3, $4, $5, $6) RETURNING id";
        this.insertQuery = pool.preparedQuery(insertSql);

        HttpServerOptions serverOptions = new HttpServerOptions()
            .setPort(cfg.appPort)
            .setHost("0.0.0.0")
            .setReuseAddress(true)
            .setTcpNoDelay(true)
            .setCompressionSupported(false)
            .setDecompressionSupported(false)
            .setIdleTimeout(60)
            .setAcceptBacklog(4096);
        if (nativeTransport) {
            serverOptions.setReusePort(true).setTcpQuickAck(true).setTcpFastOpen(true);
        }

        this.server = vertx.createHttpServer(serverOptions);
        this.server.requestHandler(this::handleRequest);
        return this.server.listen();
    }

    private void handleRequest(HttpServerRequest req) {
        String path = req.path();
        HttpMethod method = req.method();

        if ("/api/users".equals(path)) {
            if (method == HttpMethod.GET) {
                handleGetUsers(req);
                return;
            }
            if (method == HttpMethod.POST) {
                handlePostUser(req);
                return;
            }
        } else if ("/healthz".equals(path) && method == HttpMethod.GET) {
            req.response().setStatusCode(200).end(OK_BODY);
            return;
        }

        req.response().setStatusCode(404).end(NOT_FOUND_BODY);
    }

    private void handleGetUsers(HttpServerRequest req) {
        User[] users = new User[] {
            new User(1, "David D. Patton", "1670 Stiles Street", "412-578-3857", "user.png", null, null),
            new User(2, "Gary E. Eaton", "828 Collins Avenue", "614-866-1660", "user.png", null, null),
            new User(3, "John J. Fox", "1895 Columbia Mine Road", "304-505-3622", "user.png", null, null)
        };

        Buffer body = JsonCodec.encodeUsers(users);
        req.response()
            .setStatusCode(200)
            .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
            .end(body);
    }

    private void handlePostUser(HttpServerRequest req) {
        req.body().onComplete(bodyRes -> {
            HttpServerResponse resp = req.response();
            if (bodyRes.failed()) {
                resp.setStatusCode(400)
                    .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                    .end(INVALID_JSON_BODY);
                return;
            }

            CreateUserRequest incoming;
            try {
                incoming = JsonCodec.decodeCreateUserRequest(bodyRes.result());
            } catch (IllegalArgumentException e) {
                resp.setStatusCode(400)
                    .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                    .end(JsonCodec.encodeError(e.getMessage()));
                return;
            }

            if (incoming.name() == null) {
                resp.setStatusCode(400)
                    .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                    .end(INVALID_JSON_BODY);
                return;
            }

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            long epochMs = now.toInstant().toEpochMilli();
            String image = imagePrefix + epochMs + ".png";

            insertQuery.execute(Tuple.of(
                    incoming.name(),
                    incoming.address(),
                    incoming.phone(),
                    image,
                    now,
                    now))
                .onComplete(dbRes -> {
                    if (dbRes.failed()) {
                        Throwable err = dbRes.cause();
                        System.err.println("DB insert error: " + err);
                        resp.setStatusCode(400)
                            .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                            .end(JsonCodec.encodeError(err != null ? err.getMessage() : "db error"));
                        return;
                    }

                    int id = dbRes.result().iterator().next().getInteger(0);
                    User created = new User(
                        id,
                        incoming.name(),
                        incoming.address(),
                        incoming.phone(),
                        image,
                        now,
                        now
                    );

                    resp.setStatusCode(201)
                        .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                        .end(JsonCodec.encodeUser(created));
                });
        });
    }

    @Override
    public Future<?> stop() {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> poolClose = pool != null ? pool.close() : Future.succeededFuture();
        return Future.all(serverClose, poolClose);
    }
}
