package com.unfurl.fabric.studio;

import java.nio.file.Path;

public final class StudioServerLauncher {

    public static void main(String[] args) throws Exception {
        StudioMicroserviceConfig options = Options.parse(args);
        StudioServer server = new StudioServer(options);
        if (server.nonLoopbackBindWarningRequired()) {
            System.err.println("StudioServer is now reachable from non-loopback addresses; "
                    + "no authentication is configured; this is intended for dev only.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Unfurl Fabric Studio server listening on http://"
                + server.bindAddress() + ":" + server.port());
        Thread.currentThread().join();
    }

    static final class Options {
        static StudioMicroserviceConfig parse(String[] args) {
            String bind = StudioServer.DEFAULT_BIND_ADDRESS;
            int port = StudioServer.DEFAULT_PORT;
            Path statePath = StudioStateStore.defaultPath();
            // Asset-root default resolution: -D system property first, env
            // var second, null otherwise (constructor will then fall back to
            // the bundled fixture root). The --asset-root CLI flag overrides
            // both. Mirrors StudioCatalogService.defaultAssetRoot() so the
            // same env/property convention works regardless of who is
            // constructing the catalog service.
            Path assetRoot = resolveDefaultAssetRoot();
            String eventBus = null;
            String redisUrl = null;
            String kafkaBootstrapServers = null;
            String kafkaTopic = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--bind" -> bind = value(args, ++i, "--bind");
                    case "--port" -> port = Integer.parseInt(value(args, ++i, "--port"));
                    case "--state-path" -> statePath = Path.of(value(args, ++i, "--state-path"));
                    case "--asset-root" -> assetRoot = Path.of(value(args, ++i, "--asset-root"));
                    case "--event-bus" -> eventBus = value(args, ++i, "--event-bus");
                    case "--redis-url" -> redisUrl = value(args, ++i, "--redis-url");
                    case "--kafka-bootstrap-servers" ->
                            kafkaBootstrapServers = value(args, ++i, "--kafka-bootstrap-servers");
                    case "--kafka-topic" -> kafkaTopic = value(args, ++i, "--kafka-topic");
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return new StudioMicroserviceConfig(
                    bind,
                    port,
                    statePath,
                    assetRoot,
                    eventBus,
                    redisUrl,
                    kafkaBootstrapServers,
                    kafkaTopic);
        }

        private static String value(String[] args, int index, String flag) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }

        private static Path resolveDefaultAssetRoot() {
            String configured = System.getProperty("unfurl.studio.asset.root");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("UNFURL_STUDIO_ASSET_ROOT");
            }
            return configured == null || configured.isBlank() ? null : Path.of(configured);
        }
    }

    private StudioServerLauncher() {
    }
}
