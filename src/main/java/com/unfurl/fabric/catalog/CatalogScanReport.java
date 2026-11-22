package com.unfurl.fabric.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Operator-facing diagnostic wrapper around a deterministic Catalog. Carries the volatile
 * fields excluded from the canonical Catalog record (scan time, source directory, skipped entries, warnings).
 * Never participates in canonical bytes used for signing.
 *
 * <p>Pattern: immutable <b>value object</b> (diagnostic envelope around {@link Catalog}).
 *
 * @param catalog        the deterministic catalog snapshot (defaults to empty).
 * @param scannedAt      when the scan ran (required).
 * @param scanSource     the scanned source directory (required).
 * @param skippedEntries JARs skipped during the scan, with reasons.
 * @param warnings       problems that do not skip an entry, such as DCP's {@code CLAIM_DOCUMENTATION_HINT_ONLY}
 *                       (HLD-api-documentation-artifacts §2), in scan order.
 */
public record CatalogScanReport(
        Catalog catalog,
        Instant scannedAt,
        Path scanSource,
        List<SkippedEntry> skippedEntries,
        List<ScanWarning> warnings
) {
    /** Compatibility constructor: a scan without warnings. */
    public CatalogScanReport(Catalog catalog, Instant scannedAt, Path scanSource, List<SkippedEntry> skippedEntries) {
        this(catalog, scannedAt, scanSource, skippedEntries, List.of());
    }

    /**
     * Value object: one warning about a scanned jar that is still selectable.
     *
     * @param jarPath the jar the warning is about
     * @param code    DCP's diagnostic code, for example {@code CLAIM_DOCUMENTATION_HINT_ONLY}
     * @param detail  the message and the claim field it concerns
     */
    public record ScanWarning(Path jarPath, String code, String detail) { }

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
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
