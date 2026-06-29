package com.unfurl.fabric.matcher;

import com.unfurl.fabric.trust.RejectedEntry;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of running the structural matcher against an allowed catalog. The three variants
 * are mutually exclusive: {@link ExactMatch} has exactly one valid candidate, {@link Ambiguous}
 * has more than one (deterministically ranked), and {@link NoMatch} has none — in which case
 * the result also carries any catalog entries the trust policy had previously rejected, so
 * diagnostics can say "this capability is in the catalog but was rejected because vendor=X"
 * rather than the less useful "no provider found".
 *
 * <p>Pattern: <b>sealed interface as a sum type</b> over the three mutually-exclusive outcomes.
 */
public sealed interface MatchResult permits MatchResult.ExactMatch, MatchResult.Ambiguous, MatchResult.NoMatch {

    /**
     * Exactly one valid composition candidate was found.
     *
     * @param candidate the single valid candidate (required).
     */
    record ExactMatch(CompositionCandidate candidate) implements MatchResult {
        /** Compact constructor: requires a non-null candidate. */
        public ExactMatch {
            if (candidate == null) {
                throw new IllegalArgumentException("candidate is required");
            }
        }
    }

    /**
     * More than one valid candidate was found, deterministically ranked best-first.
     *
     * @param candidates the ranked candidates (at least two).
     */
    record Ambiguous(List<CompositionCandidate> candidates) implements MatchResult {
        /** Compact constructor: requires ≥2 candidates and defensively copies the list. */
        public Ambiguous {
            if (candidates == null || candidates.size() < 2) {
                throw new IllegalArgumentException("ambiguous requires at least two candidates");
            }
            candidates = List.copyOf(candidates);
        }

        /**
         * Look up a candidate by its deterministic id (used by {@code fabric compile --select <id>}).
         *
         * @param candidateId the candidate id.
         * @return the matching candidate, or empty if absent/blank id.
         */
        public Optional<CompositionCandidate> findById(String candidateId) {
            if (candidateId == null || candidateId.isBlank()) {
                return Optional.empty();
            }
            return candidates.stream()
                    .filter(c -> candidateId.equals(c.candidateId()))
                    .findFirst();
        }
    }

    /**
     * No valid composition was found.
     *
     * @param missing                       required capabilities with no allowed provider.
     * @param conflicts                     conflicts from otherwise-considered shapes.
     * @param potentiallyRelevantRejections trust-rejected entries that could have provided the missing caps.
     */
    record NoMatch(
            List<UnmetCapabilityRequirement> missing,
            List<Conflict> conflicts,
            List<RejectedEntry> potentiallyRelevantRejections
    ) implements MatchResult {
        /** Compact constructor: defensively copies all three diagnostic lists. */
        public NoMatch {
            missing = missing == null ? List.of() : List.copyOf(missing);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            potentiallyRelevantRejections = potentiallyRelevantRejections == null
                    ? List.of() : List.copyOf(potentiallyRelevantRejections);
        }
    }
}
