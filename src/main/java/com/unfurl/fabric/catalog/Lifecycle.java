package com.unfurl.fabric.catalog;

import java.time.LocalDate;

public record Lifecycle(
        LifecycleStatus status,
        LocalDate since,
        LocalDate deprecatedAfter,
        String replacement
) {
    public Lifecycle {
        if (status == null) {
            throw new IllegalArgumentException("lifecycle status is required");
        }
    }

    public static Lifecycle active() {
        return new Lifecycle(LifecycleStatus.ACTIVE, null, null, null);
    }
}
