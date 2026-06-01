package com.unfurl.fabric.compiler;

public final class ContractCompileException extends RuntimeException {
    public ContractCompileException(String message) {
        super(message);
    }

    public ContractCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
