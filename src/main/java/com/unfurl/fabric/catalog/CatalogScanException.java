package com.unfurl.fabric.catalog;

/**
 * Unchecked exception for catalog scanning/parsing failures (unreadable directory, malformed manifest,
 * missing required blocks).
 *
 * <p>Pattern: domain-specific <b>runtime exception</b> wrapping lower-level I/O/parse errors.
 */
public class CatalogScanException extends RuntimeException {
    /**
     * @param message human-readable failure description.
     */
    public CatalogScanException(String message) {
        super(message);
    }

    /**
     * @param message human-readable failure description.
     * @param cause   the underlying cause.
     */
    public CatalogScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
