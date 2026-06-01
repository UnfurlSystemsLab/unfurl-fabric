package com.unfurl.fabric.cli;

final class FabricCliException extends RuntimeException {
    private final int exitCode;

    FabricCliException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }

    static FabricCliException usage(String message) {
        return new FabricCliException(2, message);
    }

    static FabricCliException runtime(String message) {
        return new FabricCliException(1, message);
    }
}
