package com.unfurl.fabric.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic snapshot of catalog entries. Excludes any runtime/diagnostic metadata so
 * two scans of the same on-disk catalog produce byte-identical Catalog records. Operator
 * diagnostics (scan time, source directory, skipped entries) live on CatalogScanReport.
 *
 * <p>{@code companions} is the companion index (HLD-in-process-component-hosting §3): every unambiguous companion jar
 * by its {@code group:artifact:version}. Compile pins the companions a hosted entry names from here.
 *
 * <p>Pattern: immutable <b>value object</b> whose compact constructor canonicalizes ordering.
 *
 * @param entries    the catalog entries (sorted into {@link CatalogEntry#CANONICAL_ORDER}).
 * @param companions the companion jars by coordinates, sorted; empty when the catalog has none.
 */
public record Catalog(List<CatalogEntry> entries, Map<String, CompanionArtifact> companions) {
    /** Compact constructor: defensively copies and sorts entries into canonical order for determinism. */
    public Catalog {
        if (entries == null) {
            entries = List.of();
        } else {
            List<CatalogEntry> sorted = new ArrayList<>(entries);
            sorted.sort(CatalogEntry.CANONICAL_ORDER);
            entries = List.copyOf(sorted);
        }
        companions = companions == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(companions));
    }

    /** Compatibility constructor: a catalog without companion jars. */
    public Catalog(List<CatalogEntry> entries) {
        this(entries, Map.of());
    }

    /**
     * @return an empty catalog.
     */
    public static Catalog empty() {
        return new Catalog(List.of());
    }
}
