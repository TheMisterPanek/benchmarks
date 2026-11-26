package app;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.util.concurrent.CountDownLatch;

public final class Main {

    public static void main(String[] args) throws Exception {
        String configPath = "config.yaml";
        for (int i = 0; i < args.length; i++) {
            if ("-config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            } else if (args[i].startsWith("-config=")) {
                configPath = args[i].substring("-config=".length());
            }
        }

        AppConfig cfg = AppConfig.load(configPath);
        String tableName = validateTableName(cfg.dbUser);
        String appName = System.getenv("APP_NAME");
        if (appName == null || appName.isEmpty()) {
            appName = "java25-blazing";
        }
        String imagePrefix = "user-" + appName + "-";

        int cpuCount = Runtime.getRuntime().availableProcessors();
        int instances = Math.max(1, cpuCount);
        int poolSizePerVerticle = Math.max(2, cfg.dbMaxConnections / instances);

        VertxOptions vertxOptions = new VertxOptions()
            .setEventLoopPoolSize(instances)
            .setPreferNativeTransport(true)
            .setBlockedThreadCheckInterval(60_000);

        Vertx vertx = Vertx.vertx(vertxOptions);
        boolean nativeTransport = vertx.isNativeTransportEnabled();

        // SO_REUSEPORT is only supported with native transport; otherwise deploy 1 instance
        int deployInstances = nativeTransport ? instances : 1;
        int effectivePoolPerVerticle = nativeTransport ? poolSizePerVerticle : cfg.dbMaxConnections;

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] startError = new Throwable[1];

        vertx.deployVerticle(
                () -> new ServerVerticle(cfg, tableName, imagePrefix, effectivePoolPerVerticle),
                new DeploymentOptions().setInstances(deployInstances))
            .onComplete(ar -> {
                if (ar.failed()) {
                    startError[0] = ar.cause();
                }
                latch.countDown();
            });

        latch.await();
        if (startError[0] != null) {
            startError[0].printStackTrace(System.err);
            vertx.close();
            System.exit(1);
        }

        System.out.printf(
            "Java JIT (blazing Vert.x) listening on http://0.0.0.0:%d (instances=%d, nativeEpoll=%s, poolPerVerticle=%d, table=%s)%n",
            cfg.appPort, deployInstances, nativeTransport, effectivePoolPerVerticle, tableName
        );
    }

    private static String validateTableName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "java_blazing_app";
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                throw new IllegalArgumentException("Invalid table name: " + raw);
            }
        }
        return raw;
    }
}
