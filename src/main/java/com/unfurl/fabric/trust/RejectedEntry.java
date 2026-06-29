package com.unfurl.fabric.trust;

import com.unfurl.fabric.catalog.CatalogEntry;

import java.util.List;

/**
 * Immutable pairing of a rejected {@link CatalogEntry} with the non-empty list of
 * {@link RejectionReason}s that excluded it. Produced by {@link TrustClassifier} and consumed by
 * diagnostic commands so operators see exactly why an entry was excluded.
 *
 * <p>Pattern: <b>value object</b> with an invariant-enforcing compact constructor.
 *
 * @param entry   the rejected catalog entry (required).
 * @param reasons the reasons it was rejected (at least one required).
 */
public record RejectedEntry(
        CatalogEntry entry,
        List<RejectionReason> reasons
) {
    /**
     * Compact constructor: requires a non-null entry and at least one reason, and defensively copies
     * the reasons list.
     */
    public RejectedEntry {
        if (entry == null) {
            throw new IllegalArgumentException("entry is required");
        }
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("at least one rejection reason is required");
        }
        reasons = List.copyOf(reasons);
    }
}
