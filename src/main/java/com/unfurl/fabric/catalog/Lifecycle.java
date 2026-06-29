package com.unfurl.fabric.catalog;

import java.time.LocalDate;

/**
 * Authored lifecycle block for a catalog entry: status plus optional provenance dates and a
 * replacement pointer.
 *
 * <p>Pattern: immutable <b>value object</b> with an {@code active()} factory.
 *
 * @param status          the lifecycle status (required).
 * @param since           when the component reached this status (optional).
 * @param deprecatedAfter when deprecation takes effect (optional).
 * @param replacement     coordinates of a recommended replacement (optional).
 */
public record Lifecycle(
        LifecycleStatus status,
        LocalDate since,
        LocalDate deprecatedAfter,
        String replacement
) {
    /** Compact constructor: requires a non-null status. */
    public Lifecycle {
        if (status == null) {
            throw new IllegalArgumentException("lifecycle status is required");
        }
    }

    /**
     * @return an ACTIVE lifecycle with no dates or replacement.
     */
    public static Lifecycle active() {
        return new Lifecycle(LifecycleStatus.ACTIVE, null, null, null);
    }
}
