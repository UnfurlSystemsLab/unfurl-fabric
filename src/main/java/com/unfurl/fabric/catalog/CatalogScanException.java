package com.unfurl.fabric.catalog;

public class CatalogScanException extends RuntimeException {
    public CatalogScanException(String message) {
        super(message);
    }

    public CatalogScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
