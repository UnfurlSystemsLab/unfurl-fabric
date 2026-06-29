package com.unfurl.fabric.trust;

import com.unfurl.fabric.catalog.CatalogEntry;

import java.util.List;

/**
 * Result of running a {@link TrustPolicy} over a catalog. Both halves are preserved: the
 * matcher operates over {@code allowedEntries}, while diagnostic commands surface
 * {@code rejectedEntries} so operators see why specific catalog entries were excluded.
 *
 * <p>Pattern: immutable <b>value object</b> (a two-sided partition result).
 *
 * @param allowedEntries  entries that passed the trust policy and are eligible for matching.
 * @param rejectedEntries entries excluded by the policy, each with its rejection reasons.
 */
public record TrustClassification(
        List<CatalogEntry> allowedEntries,
        List<RejectedEntry> rejectedEntries
) {
    /** Compact constructor: defensively copies both partitions (null → empty). */
    public TrustClassification {
        allowedEntries = allowedEntries == null ? List.of() : List.copyOf(allowedEntries);
        rejectedEntries = rejectedEntries == null ? List.of() : List.copyOf(rejectedEntries);
    }
}
