package com.unfurl.fabric.matcher;

import com.unfurl.fabric.catalog.CatalogEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A validated set of catalog entries that together satisfy the operator's required
 * capabilities. By construction every CompositionCandidate is valid — invalid combinations
 * produce {@link MatchResult.NoMatch} instead. Warnings cover optional-impact diagnostics
 * (deprecated component selected, optional dep unresolved) that don't break validity.
 *
 * <p>{@link #candidateId} is a deterministic, content-pinned identifier derived from the
 * sorted {@code coordinates:sha256} pairs of the selected entries. Operators use it with
 * {@code fabric compile --select <id>} to pick exactly one composition from an
 * {@link MatchResult.Ambiguous} result. Same entry set + same SHAs → same ID; if any JAR's
 * content changes, the ID changes too (which is the right semantics — the composition is
 * not the same composition).
 */
public record CompositionCandidate(
        String candidateId,
        List<CatalogEntry> entries,
        Set<String> satisfiedRequiredCapabilities,
        Set<String> satisfiedOptionalCapabilities,
        List<DependencyBinding> dependencyBindings,
        List<PlanningWarning> warnings,
        CandidateScore score
) {
    public CompositionCandidate {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId is required");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
        satisfiedRequiredCapabilities = satisfiedRequiredCapabilities == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(satisfiedRequiredCapabilities));
        satisfiedOptionalCapabilities = satisfiedOptionalCapabilities == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(satisfiedOptionalCapabilities));
        dependencyBindings = dependencyBindings == null ? List.of() : List.copyOf(dependencyBindings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (score == null) {
            throw new IllegalArgumentException("score is required");
        }
    }

    /**
     * Deterministic candidate ID over the sorted {@code coordinates:sha256} pairs of the
     * given entries. Format: {@code cand-<12 hex chars>} — short enough for operator CLI
     * use, long enough for collision resistance within a single planning run.
     */
    public static String computeId(List<CatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "cand-empty";
        }
        List<String> lines = entries.stream()
                .map(e -> e.artifact().coordinates() + ":" + e.artifact().sha256())
                .sorted()
                .toList();
        String joined = String.join("\n", lines);
        byte[] hash = sha256(joined.getBytes(StandardCharsets.UTF_8));
        return "cand-" + hexLower(hash).substring(0, 12);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available on this JVM", ex);
        }
    }

    private static String hexLower(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
