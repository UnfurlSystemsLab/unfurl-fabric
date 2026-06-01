package com.unfurl.fabric.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Operator-facing diagnostic wrapper around a deterministic Catalog. Carries the volatile
 * fields excluded from the canonical Catalog record (scan time, source directory, skipped entries).
 * Never participates in canonical bytes used for signing.
 */
public record CatalogScanReport(
        Catalog catalog,
        Instant scannedAt,
        Path scanSource,
        List<SkippedEntry> skippedEntries
) {
    public CatalogScanReport {
        if (catalog == null) {
            catalog = Catalog.empty();
        }
        if (scannedAt == null) {
            throw new IllegalArgumentException("scannedAt is required");
        }
        if (scanSource == null) {
            throw new IllegalArgumentException("scanSource is required");
        }
        skippedEntries = skippedEntries == null ? List.of() : List.copyOf(skippedEntries);
    }
}
