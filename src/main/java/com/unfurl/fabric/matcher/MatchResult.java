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
 */
public sealed interface MatchResult permits MatchResult.ExactMatch, MatchResult.Ambiguous, MatchResult.NoMatch {

    record ExactMatch(CompositionCandidate candidate) implements MatchResult {
        public ExactMatch {
            if (candidate == null) {
                throw new IllegalArgumentException("candidate is required");
            }
        }
    }

    record Ambiguous(List<CompositionCandidate> candidates) implements MatchResult {
        public Ambiguous {
            if (candidates == null || candidates.size() < 2) {
                throw new IllegalArgumentException("ambiguous requires at least two candidates");
            }
            candidates = List.copyOf(candidates);
        }

        public Optional<CompositionCandidate> findById(String candidateId) {
            if (candidateId == null || candidateId.isBlank()) {
                return Optional.empty();
            }
            return candidates.stream()
                    .filter(c -> candidateId.equals(c.candidateId()))
                    .findFirst();
        }
    }

    record NoMatch(
            List<UnmetCapabilityRequirement> missing,
            List<Conflict> conflicts,
            List<RejectedEntry> potentiallyRelevantRejections
    ) implements MatchResult {
        public NoMatch {
            missing = missing == null ? List.of() : List.copyOf(missing);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            potentiallyRelevantRejections = potentiallyRelevantRejections == null
                    ? List.of() : List.copyOf(potentiallyRelevantRejections);
        }
    }
}
