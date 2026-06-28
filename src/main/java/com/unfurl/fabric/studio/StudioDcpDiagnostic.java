package com.unfurl.fabric.studio;

/**
 * Data Transfer Object: exposes a DCP validation diagnostic on Studio's JSON API.
 * The record intentionally carries only the UI-actionable fields from the protocol
 * diagnostic so catalog admission can show exact claim paths without leaking unrelated
 * runtime metadata.
 */
public record StudioDcpDiagnostic(
        String severity,
        String code,
        String path,
        String message
) {
    /**
     * Constructor invariant: null diagnostic fields become empty strings so TypeScript
     * clients can render the response without defensive null checks.
     */
    public StudioDcpDiagnostic {
        severity = severity == null ? "" : severity;
        code = code == null ? "" : code;
        path = path == null ? "" : path;
        message = message == null ? "" : message;
    }
}
