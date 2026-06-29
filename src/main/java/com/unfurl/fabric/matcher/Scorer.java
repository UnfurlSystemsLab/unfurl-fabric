package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.LifecycleStatus;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;

/**
 * Scores already-valid candidates. Never gates validity. Weights are checked-in constants on
 * {@link CandidateScore.Weights} so two runs over the same inputs always produce the same score.
 *
 * <p>Pattern: stateless <b>strategy/calculator</b> — each {@code compute*} method contributes one
 * additive term; the total is summed by {@link CandidateScore#of}.
 */
public final class Scorer {

    /**
     * Compute the full deterministic score for an entry set against a need.
     *
     * @param entries the (already valid) selected entries.
     * @param need    the operator need.
     * @return the candidate score with its summed total.
     */
    public CandidateScore score(java.util.List<CatalogEntry> entries, Need need) {
        int optional = countOptionalCapsSatisfied(entries, need) * CandidateScore.Weights.OPTIONAL_CAPABILITY;
        int version = computeVersionPreference(entries, need);
        int dependencyResolution = CandidateScore.Weights.DEPENDENCY_RESOLUTION;
        int trust = CandidateScore.Weights.TRUST;
        int stability = computeStabilityScore(entries);
        int lifecycle = computeLifecycleScore(entries);
        int footprint = entries.size() * CandidateScore.Weights.FOOTPRINT_PENALTY;
        int risk = computeRiskFlagPenalty(entries);

        return CandidateScore.of(
                optional,
                version,
                dependencyResolution,
                trust,
                stability,
                lifecycle,
                footprint,
                risk);
    }

    /**
     * Count optional capabilities satisfied by at least one entry.
     *
     * @param entries the entries.
     * @param need    the need.
     * @return number of satisfied optional capabilities.
     */
    private static int countOptionalCapsSatisfied(java.util.List<CatalogEntry> entries, Need need) {
        int count = 0;
        for (CapabilityRequirement req : need.optionalCapabilities()) {
            outer:
            for (CatalogEntry e : entries) {
                for (Offer o : e.claimDescriptor().claim().offers()) {
                    if (o.capability().equals(req.capability())
                            && req.capabilityVersion().satisfiedBy(o.version())) {
                        count++;
                        break outer;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Reward matched required-capability versions (one weighted point per match). Deterministic; the AI
     * advisor refines within tied scores.
     *
     * @param entries the entries.
     * @param need    the need.
     * @return the weighted version-preference score.
     */
    private static int computeVersionPreference(java.util.List<CatalogEntry> entries, Need need) {
        // Reward when offers in the chosen entries are at the high end of the requested range.
        // Conservative weight: one point per required-cap match. Deterministic, no
        // version-comparison magic; AI advisor can refine within tied scores.
        int matches = 0;
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            for (CatalogEntry e : entries) {
                for (Offer o : e.claimDescriptor().claim().offers()) {
                    if (o.capability().equals(req.capability())
                            && req.capabilityVersion().satisfiedBy(o.version())) {
                        matches++;
                        break;
                    }
                }
            }
        }
        return matches * CandidateScore.Weights.VERSION_PREFERENCE;
    }

    /**
     * Sum the stability weight of every offer, scaled by the stability weight constant.
     *
     * @param entries the entries.
     * @return the weighted stability score.
     */
    private static int computeStabilityScore(java.util.List<CatalogEntry> entries) {
        int total = 0;
        for (CatalogEntry e : entries) {
            for (Offer o : e.claimDescriptor().claim().offers()) {
                total += stabilityWeight(o.stability());
            }
        }
        return total * CandidateScore.Weights.STABILITY;
    }

    /**
     * Per-offer stability weight (STABLE &gt; EVOLVING &gt; EXPERIMENTAL &gt; DEPRECATED).
     *
     * @param stability the offer stability (null → 0).
     * @return the weight.
     */
    private static int stabilityWeight(Stability stability) {
        if (stability == null) {
            return 0;
        }
        return switch (stability) {
            case STABLE -> 3;
            case EVOLVING -> 2;
            case EXPERIMENTAL -> 1;
            case DEPRECATED -> -1;
        };
    }

    /**
     * Sum the lifecycle weight of every entry, scaled by the lifecycle weight constant.
     *
     * @param entries the entries.
     * @return the weighted lifecycle score.
     */
    private static int computeLifecycleScore(java.util.List<CatalogEntry> entries) {
        int total = 0;
        for (CatalogEntry e : entries) {
            total += lifecycleWeight(e.metadata().lifecycle().status());
        }
        return total * CandidateScore.Weights.LIFECYCLE;
    }

    /**
     * Per-entry lifecycle weight (ACTIVE best; BLOCKED worst).
     *
     * @param status the lifecycle status.
     * @return the weight.
     */
    private static int lifecycleWeight(LifecycleStatus status) {
        return switch (status) {
            case ACTIVE -> 3;
            case EXPERIMENTAL -> 1;
            case DEPRECATED -> -1;
            case RETIRED -> -3;
            case BLOCKED -> -10;
        };
    }

    /**
     * Penalty for risky selections (DEPRECATED or RETIRED entries).
     *
     * @param entries the entries.
     * @return the total (negative) risk penalty.
     */
    private static int computeRiskFlagPenalty(java.util.List<CatalogEntry> entries) {
        int penalty = 0;
        for (CatalogEntry e : entries) {
            if (e.metadata().lifecycle().status() == LifecycleStatus.DEPRECATED) {
                penalty += CandidateScore.Weights.RISK_FLAG_PENALTY;
            }
            if (e.metadata().lifecycle().status() == LifecycleStatus.RETIRED) {
                penalty += CandidateScore.Weights.RISK_FLAG_PENALTY;
            }
        }
        return penalty;
    }
}
