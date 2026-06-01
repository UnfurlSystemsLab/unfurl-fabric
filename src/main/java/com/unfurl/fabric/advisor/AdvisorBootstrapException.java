package com.unfurl.fabric.advisor;

public class AdvisorBootstrapException extends RuntimeException {
    public AdvisorBootstrapException(String message) {
        super(message);
    }

    public AdvisorBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
