package com.unfurl.fabric.verify;

import java.util.List;

/**
 * Diagnostic output of {@link CatalogDriftChecker}. Operationally independent of signature
 * verification: a signed contract may pass cryptographic verification cleanly even if the
 * catalog on disk has changed since signing. This report surfaces both kinds of drift so
 * operators can decide whether to re-compile or accept the differences.
 */
public record CatalogDriftReport(
        List<DriftEntry> hashDrifts,
        List<MissingEntry> missingFromCatalog,
        boolean clean
) {
    public CatalogDriftReport {
        hashDrifts = hashDrifts == null ? List.of() : List.copyOf(hashDrifts);
        missingFromCatalog = missingFromCatalog == null ? List.of() : List.copyOf(missingFromCatalog);
    }

    public static CatalogDriftReport noDrift() {
        return new CatalogDriftReport(List.of(), List.of(), true);
    }

    /** A selection whose contracted SHA-256 no longer matches the JAR at the same coordinates. */
    public record DriftEntry(
            String coordinates,
            String contractedSha256,
            String catalogSha256,
            String detail
    ) {
        public DriftEntry(String coordinates, String contractedSha256, String catalogSha256) {
            this(coordinates, contractedSha256, catalogSha256,
                    coordinates + " sha256 drift: contracted " + shortSha(contractedSha256)
                            + " but catalog has " + shortSha(catalogSha256));
        }
    }

    /** A selection whose coordinates are not in the current catalog at all. */
    public record MissingEntry(String coordinates, String contractedSha256, String detail) {
        public MissingEntry(String coordinates, String contractedSha256) {
            this(coordinates, contractedSha256,
                    coordinates + " (sha " + shortSha(contractedSha256) + ") is not in the current catalog");
        }
    }

    private static String shortSha(String fullSha) {
        if (fullSha == null || fullSha.length() < 12) {
            return String.valueOf(fullSha);
        }
        return fullSha.substring(0, 12) + "…";
    }
}
