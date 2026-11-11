package com.unfurl.fabric.catalog;

import java.nio.file.Path;

/**
 * Diagnostic record of a JAR the scanner skipped (rather than failed on), with the reason and detail.
 * Surfaced on {@link CatalogScanReport} so operators can see which JARs were excluded and why.
 *
 * <p>Pattern: immutable <b>value object</b> with a nested reason enum.
 *
 * @param jarPath the skipped JAR's path (required).
 * @param reason  the skip category (required).
 * @param detail  human-readable detail (defaults to empty).
 */
public record SkippedEntry(
        Path jarPath,
        SkipReason reason,
        String detail
) {
    /** Compact constructor: requires jarPath and reason; normalizes a null detail to empty. */
    public SkippedEntry {
        if (jarPath == null) {
            throw new IllegalArgumentException("jarPath is required");
        }
        if (reason == null) {
            throw new IllegalArgumentException("skip reason is required");
        }
        detail = detail == null ? "" : detail;
    }

    /** Why a JAR was skipped during scanning. */
    public enum SkipReason {
        /** The JAR file could not be read. */
        UNREADABLE_JAR,
        /** The JAR has no {@code META-INF/unfurl-catalog.yaml}. */
        MISSING_MANIFEST,
        /** The manifest was present but could not be parsed. */
        MALFORMED_MANIFEST,
        /** The manifest parsed but lacked a required field/block. */
        MISSING_REQUIRED_FIELD,
        /**
         * A hosted component whose {@code hosted_activation.companions} cannot be resolved: a companion is absent
         * ({@code CATALOG_COMPANION_MISSING}) or several jars hold different bytes under its coordinates
         * ({@code CATALOG_COMPANION_CONFLICT}). The detail starts with the code. The entry is not selectable.
         */
        COMPANION_UNRESOLVED,
        /**
         * The packaged {@code META-INF/unfurl-config.yaml} is invalid by DCP's schema rules
         * (HLD-in-process-component-hosting §6). The detail lists every failure. The entry is not selectable.
         */
        INVALID_COMPONENT_CONFIGURATION
    }
}
