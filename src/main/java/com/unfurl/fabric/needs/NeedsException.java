package com.unfurl.fabric.needs;

public class NeedsException extends RuntimeException {
    public NeedsException(String message) {
        super(message);
    }

    public NeedsException(String message, Throwable cause) {
        super(message, cause);
    }
}
