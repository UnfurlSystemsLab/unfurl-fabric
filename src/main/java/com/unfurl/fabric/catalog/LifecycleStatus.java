package com.unfurl.fabric.catalog;

/**
 * Operator-facing lifecycle status of a catalog component, governing selectability and scoring.
 *
 * <p>Pattern: simple <b>enum</b> (closed value set).
 */
public enum LifecycleStatus {
    /** Pre-release/early; allowed but scored low. */
    EXPERIMENTAL,
    /** Fully supported; the preferred status. */
    ACTIVE,
    /** Still usable but discouraged; penalized in scoring. */
    DEPRECATED,
    /** No longer supported; heavily penalized. */
    RETIRED,
    /** Explicitly disallowed; effectively unselectable. */
    BLOCKED
}
