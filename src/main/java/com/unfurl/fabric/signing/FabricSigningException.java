package com.unfurl.fabric.signing;

public class FabricSigningException extends RuntimeException {
    public FabricSigningException(String message) {
        super(message);
    }

    public FabricSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
