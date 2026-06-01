package com.unfurl.fabric.matcher;

/**
 * Deterministic scoring over an already-valid {@link CompositionCandidate}. Validity is a
 * gate, not a score dimension — a candidate exists only after passing validation, so this
 * record never includes "required capability satisfied" terms. The AI advisor may re-rank
 * within valid candidates but never alters the math here.
 */
public record CandidateScore(
        int optionalCapabilityScore,
        int versionPreferenceScore,
        int dependencyResolutionScore,
        int trustScore,
        int stabilityScore,
        int lifecycleScore,
        int footprintPenalty,
        int riskFlagPenalty,
        int finalScore
) {

    public static final class Weights {
        public static final int OPTIONAL_CAPABILITY = 20;
        public static final int VERSION_PREFERENCE = 5;
        public static final int DEPENDENCY_RESOLUTION = 10;
        public static final int TRUST = 5;
        public static final int STABILITY = 10;
        public static final int LIFECYCLE = 10;
        public static final int FOOTPRINT_PENALTY = -3;
        public static final int RISK_FLAG_PENALTY = -10;

        private Weights() {
        }
    }

    public static CandidateScore of(
            int optionalCapabilityScore,
            int versionPreferenceScore,
            int dependencyResolutionScore,
            int trustScore,
            int stabilityScore,
            int lifecycleScore,
            int footprintPenalty,
            int riskFlagPenalty) {

        int finalScore = optionalCapabilityScore
                + versionPreferenceScore
                + dependencyResolutionScore
                + trustScore
                + stabilityScore
                + lifecycleScore
                + footprintPenalty
                + riskFlagPenalty;

        return new CandidateScore(
                optionalCapabilityScore,
                versionPreferenceScore,
                dependencyResolutionScore,
                trustScore,
                stabilityScore,
                lifecycleScore,
                footprintPenalty,
                riskFlagPenalty,
                finalScore);
    }
}
