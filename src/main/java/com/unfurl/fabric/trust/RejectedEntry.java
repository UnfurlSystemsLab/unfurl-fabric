package com.unfurl.fabric.trust;

import com.unfurl.fabric.catalog.CatalogEntry;

import java.util.List;

public record RejectedEntry(
        CatalogEntry entry,
        List<RejectionReason> reasons
) {
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
