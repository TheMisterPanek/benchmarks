package app

import io.vertx.core.Deployable
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import java.util.concurrent.CountDownLatch
import java.util.function.Supplier
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var configPath = "config.yaml"
    var i = 0
    while (i < args.size) {
        when {
            args[i] == "-config" && i + 1 < args.size -> configPath = args[++i]
            args[i].startsWith("-config=") -> configPath = args[i].substring("-config=".length)
        }
        i++
    }

    val cfg = AppConfig.load(configPath)
    val tableName = validateTableName(cfg.dbUser)
    val appName = System.getenv("APP_NAME")?.takeIf { it.isNotEmpty() } ?: "kotlin24-blazing"
    val imagePrefix = "user-$appName-"

    val cpuCount = Runtime.getRuntime().availableProcessors()
    val instances = maxOf(1, cpuCount)
    val poolSizePerVerticle = maxOf(2, cfg.dbMaxConnections / instances)

    val vertxOptions = VertxOptions()
        .setEventLoopPoolSize(instances)
        .setPreferNativeTransport(true)
        .setBlockedThreadCheckInterval(60_000)

    val vertx = Vertx.vertx(vertxOptions)
    val nativeTransport = vertx.isNativeTransportEnabled

    val deployInstances = if (nativeTransport) instances else 1
    val effectivePoolPerVerticle = if (nativeTransport) poolSizePerVerticle else cfg.dbMaxConnections

    val latch = CountDownLatch(1)
    var startError: Throwable? = null

    vertx.deployVerticle(
        Supplier<Deployable> { ServerVerticle(cfg, tableName, imagePrefix, effectivePoolPerVerticle) },
        DeploymentOptions().setInstances(deployInstances),
    ).onComplete { ar ->
        if (ar.failed()) startError = ar.cause()
        latch.countDown()
    }

    latch.await()
    startError?.let { err ->
        err.printStackTrace(System.err)
        vertx.close()
        exitProcess(1)
    }

    println(
        "Kotlin JIT (blazing Vert.x) listening on http://0.0.0.0:${cfg.appPort} " +
            "(instances=$deployInstances, nativeEpoll=$nativeTransport, poolPerVerticle=$effectivePoolPerVerticle, table=$tableName)"
    )
}

private fun validateTableName(raw: String): String {
    if (raw.isEmpty()) return "kotlin_blazing_app"
    for (c in raw) {
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_')) {
            throw IllegalArgumentException("Invalid table name: $raw")
        }
    }
    return raw
}
