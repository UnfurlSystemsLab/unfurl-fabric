package com.unfurl.fabric.matcher;

/**
 * Deterministic scoring over an already-valid {@link CompositionCandidate}. Validity is a
 * gate, not a score dimension — a candidate exists only after passing validation, so this
 * record never includes "required capability satisfied" terms. The AI advisor may re-rank
 * within valid candidates but never alters the math here.
 *
 * <p>Pattern: immutable <b>value object</b> with a derived total ({@link #of}) and checked-in weights.
 *
 * @param optionalCapabilityScore  reward for satisfied optional capabilities.
 * @param versionPreferenceScore   reward for matching requested version ranges.
 * @param dependencyResolutionScore reward for resolved dependencies.
 * @param trustScore               trust-derived reward.
 * @param stabilityScore           offer-stability reward.
 * @param lifecycleScore           lifecycle-status reward.
 * @param footprintPenalty         penalty proportional to component count.
 * @param riskFlagPenalty          penalty for risky (deprecated/retired) selections.
 * @param finalScore               the summed total (the sort key).
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

    /**
     * Checked-in scoring weights (constants), so the same inputs always yield the same score across runs.
     *
     * <p>Pattern: non-instantiable <b>constants holder</b>.
     */
    public static final class Weights {
        /** Points per satisfied optional capability. */
        public static final int OPTIONAL_CAPABILITY = 20;
        /** Points per matched required-capability version. */
        public static final int VERSION_PREFERENCE = 5;
        /** Flat reward when dependencies resolve. */
        public static final int DEPENDENCY_RESOLUTION = 10;
        /** Flat trust reward. */
        public static final int TRUST = 5;
        /** Multiplier applied to summed offer-stability weights. */
        public static final int STABILITY = 10;
        /** Multiplier applied to summed lifecycle weights. */
        public static final int LIFECYCLE = 10;
        /** Per-component footprint penalty (negative). */
        public static final int FOOTPRINT_PENALTY = -3;
        /** Penalty per risky selection (negative). */
        public static final int RISK_FLAG_PENALTY = -10;

        /** Non-instantiable. */
        private Weights() {
        }
    }

    /**
     * Factory computing {@code finalScore} as the sum of all component scores.
     *
     * @param optionalCapabilityScore   optional-capability reward.
     * @param versionPreferenceScore    version-preference reward.
     * @param dependencyResolutionScore dependency-resolution reward.
     * @param trustScore                trust reward.
     * @param stabilityScore            stability reward.
     * @param lifecycleScore            lifecycle reward.
     * @param footprintPenalty          footprint penalty.
     * @param riskFlagPenalty           risk penalty.
     * @return the score with the derived total.
     */
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
