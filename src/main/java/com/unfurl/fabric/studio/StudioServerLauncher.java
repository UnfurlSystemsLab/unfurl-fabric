package com.unfurl.fabric.studio;

public final class StudioServerLauncher {

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        StudioServer server = new StudioServer(options.bindAddress(), options.port());
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

    record Options(String bindAddress, int port) {
        static Options parse(String[] args) {
            String bind = StudioServer.DEFAULT_BIND_ADDRESS;
            int port = StudioServer.DEFAULT_PORT;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--bind" -> bind = value(args, ++i, "--bind");
                    case "--port" -> port = Integer.parseInt(value(args, ++i, "--port"));
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return new Options(bind, port);
        }

        private static String value(String[] args, int index, String flag) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }
    }

    private StudioServerLauncher() {
    }
}
