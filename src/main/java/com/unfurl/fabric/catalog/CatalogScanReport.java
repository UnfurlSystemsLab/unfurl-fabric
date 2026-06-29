package com.unfurl.fabric.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Operator-facing diagnostic wrapper around a deterministic Catalog. Carries the volatile
 * fields excluded from the canonical Catalog record (scan time, source directory, skipped entries).
 * Never participates in canonical bytes used for signing.
 *
 * <p>Pattern: immutable <b>value object</b> (diagnostic envelope around {@link Catalog}).
 *
 * @param catalog        the deterministic catalog snapshot (defaults to empty).
 * @param scannedAt      when the scan ran (required).
 * @param scanSource     the scanned source directory (required).
 * @param skippedEntries JARs skipped during the scan, with reasons.
 */
public record CatalogScanReport(
        Catalog catalog,
        Instant scannedAt,
        Path scanSource,
        List<SkippedEntry> skippedEntries
) {
    /** Compact constructor: defaults a null catalog to empty, requires scan time/source, copies skips. */
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
