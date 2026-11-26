package app

import io.vertx.core.Future
import io.vertx.core.VerticleBase
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.SslMode
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ServerVerticle(
    private val cfg: AppConfig,
    private val tableName: String,
    private val imagePrefix: String,
    private val poolSizePerVerticle: Int,
) : VerticleBase() {

    private var pool: Pool? = null
    private lateinit var insertQuery: PreparedQuery<RowSet<Row>>
    private var server: HttpServer? = null

    override fun start(): Future<*> {
        val nativeTransport = vertx.isNativeTransportEnabled

        val connectOptions = PgConnectOptions()
            .setHost(cfg.dbHost)
            .setPort(5432)
            .setDatabase(cfg.dbDatabase)
            .setUser(cfg.dbUser)
            .setPassword(cfg.dbPassword)
            .setSslMode(SslMode.DISABLE)
            .setCachePreparedStatements(true)
            .setPreparedStatementCacheMaxSize(32)
            .setPipeliningLimit(256)

        val poolOptions = PoolOptions()
            .setMaxSize(poolSizePerVerticle)
            .setShared(false)

        val p = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build()
        this.pool = p

        val insertSql = "INSERT INTO $tableName (name, address, phone, image, created_at, updated_at) " +
            "VALUES (\$1, \$2, \$3, \$4, \$5, \$6) RETURNING id"
        this.insertQuery = p.preparedQuery(insertSql)

        val serverOptions = HttpServerOptions()
            .setPort(cfg.appPort)
            .setHost("0.0.0.0")
            .setReuseAddress(true)
            .setTcpNoDelay(true)
            .setCompressionSupported(false)
            .setDecompressionSupported(false)
            .setIdleTimeout(60)
            .setAcceptBacklog(4096)
        if (nativeTransport) {
            serverOptions.setReusePort(true).setTcpQuickAck(true).setTcpFastOpen(true)
        }

        val s = vertx.createHttpServer(serverOptions)
        this.server = s
        s.requestHandler(::handleRequest)
        return s.listen()
    }

    private fun handleRequest(req: HttpServerRequest) {
        val path = req.path()
        val method = req.method()

        if ("/api/users" == path) {
            if (method === HttpMethod.GET) {
                handleGetUsers(req)
                return
            }
            if (method === HttpMethod.POST) {
                handlePostUser(req)
                return
            }
        } else if ("/healthz" == path && method === HttpMethod.GET) {
            req.response().setStatusCode(200).end(OK_BODY)
            return
        }

        req.response().setStatusCode(404).end(NOT_FOUND_BODY)
    }

    private fun handleGetUsers(req: HttpServerRequest) {
        val users = arrayOf(
            User(1, "David D. Patton", "1670 Stiles Street", "412-578-3857", "user.png", null, null),
            User(2, "Gary E. Eaton", "828 Collins Avenue", "614-866-1660", "user.png", null, null),
            User(3, "John J. Fox", "1895 Columbia Mine Road", "304-505-3622", "user.png", null, null),
        )

        val body = JsonCodec.encodeUsers(users)
        req.response()
            .setStatusCode(200)
            .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
            .end(body)
    }

    private fun handlePostUser(req: HttpServerRequest) {
        req.body().onComplete { bodyRes ->
            val resp = req.response()
            if (bodyRes.failed()) {
                resp.setStatusCode(400)
                    .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                    .end(INVALID_JSON_BODY)
                return@onComplete
            }

            val incoming = try {
                JsonCodec.decodeCreateUserRequest(bodyRes.result())
            } catch (e: IllegalArgumentException) {
                resp.setStatusCode(400)
                    .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                    .end(JsonCodec.encodeError(e.message))
                return@onComplete
            }

            val name = incoming.name
            if (name == null) {
                resp.setStatusCode(400)
                    .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                    .end(INVALID_JSON_BODY)
                return@onComplete
            }

            val address = incoming.address ?: ""
            val phone = incoming.phone ?: ""
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val epochMs = now.toInstant().toEpochMilli()
            val image = "$imagePrefix$epochMs.png"

            insertQuery.execute(Tuple.of(name, address, phone, image, now, now))
                .onComplete { dbRes ->
                    if (dbRes.failed()) {
                        val err = dbRes.cause()
                        System.err.println("DB insert error: $err")
                        resp.setStatusCode(400)
                            .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                            .end(JsonCodec.encodeError(err?.message ?: "db error"))
                        return@onComplete
                    }

                    val id = dbRes.result().iterator().next().getInteger(0)
                    val created = User(id, name, address, phone, image, now, now)

                    resp.setStatusCode(201)
                        .putHeader(HEADER_CONTENT_TYPE, MEDIA_JSON)
                        .end(JsonCodec.encodeUser(created))
                }
        }
    }

    override fun stop(): Future<*> {
        val serverClose = server?.close() ?: Future.succeededFuture<Void>()
        val poolClose = pool?.close() ?: Future.succeededFuture<Void>()
        return Future.all(serverClose, poolClose)
    }

    companion object {
        private val HEADER_CONTENT_TYPE: CharSequence = HttpHeaders.CONTENT_TYPE
        private val MEDIA_JSON: CharSequence = HttpHeaders.createOptimized("application/json")
        private val OK_BODY: Buffer = Buffer.buffer("OK")
        private val INVALID_JSON_BODY: Buffer = JsonCodec.encodeError("Invalid JSON body")
        private val NOT_FOUND_BODY: Buffer = Buffer.buffer("Resource not found")
    }
}
