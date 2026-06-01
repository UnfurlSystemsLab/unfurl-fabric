package com.unfurl.fabric.catalog;

import java.nio.file.Path;

public record SkippedEntry(
        Path jarPath,
        SkipReason reason,
        String detail
) {
    public SkippedEntry {
        if (jarPath == null) {
            throw new IllegalArgumentException("jarPath is required");
        }
        if (reason == null) {
            throw new IllegalArgumentException("skip reason is required");
        }
        detail = detail == null ? "" : detail;
    }

    public enum SkipReason {
        UNREADABLE_JAR,
        MISSING_MANIFEST,
        MALFORMED_MANIFEST,
        MISSING_REQUIRED_FIELD
    }
}
