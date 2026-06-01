package com.unfurl.fabric.trust;

import com.unfurl.fabric.catalog.CatalogEntry;

import java.util.List;

/**
 * Result of running a {@link TrustPolicy} over a catalog. Both halves are preserved: the
 * matcher operates over {@code allowedEntries}, while diagnostic commands surface
 * {@code rejectedEntries} so operators see why specific catalog entries were excluded.
 */
public record TrustClassification(
        List<CatalogEntry> allowedEntries,
        List<RejectedEntry> rejectedEntries
) {
    public TrustClassification {
        allowedEntries = allowedEntries == null ? List.of() : List.copyOf(allowedEntries);
        rejectedEntries = rejectedEntries == null ? List.of() : List.copyOf(rejectedEntries);
    }
}
