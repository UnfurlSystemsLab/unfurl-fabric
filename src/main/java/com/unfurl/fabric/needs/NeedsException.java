package com.unfurl.fabric.needs;

/**
 * Unchecked exception for failures reading, parsing, or writing operator {@code needs} documents.
 *
 * <p>Pattern: domain-specific <b>runtime exception</b> — wraps lower-level I/O/parse errors so callers
 * see a single needs-scoped failure type rather than raw Jackson/IO exceptions.
 */
public class NeedsException extends RuntimeException {
    /**
     * @param message human-readable failure description.
     */
    public NeedsException(String message) {
        super(message);
    }

    /**
     * @param message human-readable failure description.
     * @param cause   the underlying cause (e.g. an {@link java.io.IOException}).
     */
    public NeedsException(String message, Throwable cause) {
        super(message, cause);
    }
}
