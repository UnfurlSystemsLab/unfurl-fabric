package com.unfurl.fabric.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic snapshot of catalog entries. Excludes any runtime/diagnostic metadata so
 * two scans of the same on-disk catalog produce byte-identical Catalog records. Operator
 * diagnostics (scan time, source directory, skipped entries) live on CatalogScanReport.
 *
 * <p>Pattern: immutable <b>value object</b> whose compact constructor canonicalizes ordering.
 *
 * @param entries the catalog entries (sorted into {@link CatalogEntry#CANONICAL_ORDER}).
 */
public record Catalog(List<CatalogEntry> entries) {
    /** Compact constructor: defensively copies and sorts entries into canonical order for determinism. */
    public Catalog {
        if (entries == null) {
            entries = List.of();
        } else {
            List<CatalogEntry> sorted = new ArrayList<>(entries);
            sorted.sort(CatalogEntry.CANONICAL_ORDER);
            entries = List.copyOf(sorted);
        }
    }

    /**
     * @return an empty catalog.
     */
    public static Catalog empty() {
        return new Catalog(List.of());
    }
}
