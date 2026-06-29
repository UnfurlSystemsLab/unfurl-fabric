package com.unfurl.fabric.verify;

import java.util.List;

/**
 * Diagnostic output of {@link CatalogDriftChecker}. Operationally independent of signature
 * verification: a signed contract may pass cryptographic verification cleanly even if the
 * catalog on disk has changed since signing. This report surfaces both kinds of drift so
 * operators can decide whether to re-compile or accept the differences.
 *
 * <p>Pattern: immutable <b>value object</b> with a {@code noDrift()} factory; nested records model the
 * two drift categories.
 *
 * @param hashDrifts        selections whose contracted SHA-256 no longer matches the catalog JAR.
 * @param missingFromCatalog selections whose coordinates are absent from the current catalog.
 * @param clean             true iff there are no hash drifts and no missing entries.
 */
public record CatalogDriftReport(
        List<DriftEntry> hashDrifts,
        List<MissingEntry> missingFromCatalog,
        boolean clean
) {
    /** Compact constructor: defensively copies the drift lists (null → empty). */
    public CatalogDriftReport {
        hashDrifts = hashDrifts == null ? List.of() : List.copyOf(hashDrifts);
        missingFromCatalog = missingFromCatalog == null ? List.of() : List.copyOf(missingFromCatalog);
    }

    /**
     * Factory for the clean (no-drift) report.
     *
     * @return a report with empty drift lists and {@code clean == true}.
     */
    public static CatalogDriftReport noDrift() {
        return new CatalogDriftReport(List.of(), List.of(), true);
    }

    /**
     * A selection whose contracted SHA-256 no longer matches the JAR at the same coordinates.
     *
     * @param coordinates     the artifact coordinates.
     * @param contractedSha256 the SHA-256 recorded in the contract.
     * @param catalogSha256    the SHA-256 of the catalog JAR now at those coordinates.
     * @param detail          a precomputed human-readable description.
     */
    public record DriftEntry(
            String coordinates,
            String contractedSha256,
            String catalogSha256,
            String detail
    ) {
        /**
         * Convenience constructor that derives the {@code detail} message from the SHAs.
         *
         * @param coordinates      the artifact coordinates.
         * @param contractedSha256 the contracted SHA-256.
         * @param catalogSha256    the current catalog SHA-256.
         */
        public DriftEntry(String coordinates, String contractedSha256, String catalogSha256) {
            this(coordinates, contractedSha256, catalogSha256,
                    coordinates + " sha256 drift: contracted " + shortSha(contractedSha256)
                            + " but catalog has " + shortSha(catalogSha256));
        }
    }

    /**
     * A selection whose coordinates are not in the current catalog at all.
     *
     * @param coordinates      the artifact coordinates.
     * @param contractedSha256 the SHA-256 recorded in the contract.
     * @param detail           a precomputed human-readable description.
     */
    public record MissingEntry(String coordinates, String contractedSha256, String detail) {
        /**
         * Convenience constructor that derives the {@code detail} message.
         *
         * @param coordinates      the artifact coordinates.
         * @param contractedSha256 the contracted SHA-256.
         */
        public MissingEntry(String coordinates, String contractedSha256) {
            this(coordinates, contractedSha256,
                    coordinates + " (sha " + shortSha(contractedSha256) + ") is not in the current catalog");
        }
    }

    /**
     * Abbreviate a SHA-256 to its first 12 chars (with an ellipsis) for readable messages.
     *
     * @param fullSha the full hash, possibly null/short.
     * @return the abbreviated hash, or the input unchanged when too short/null.
     */
    private static String shortSha(String fullSha) {
        if (fullSha == null || fullSha.length() < 12) {
            return String.valueOf(fullSha);
        }
        return fullSha.substring(0, 12) + "…";
    }
}
