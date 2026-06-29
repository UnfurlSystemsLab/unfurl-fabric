package com.unfurl.fabric.matcher;

import java.util.List;

/**
 * Result of running the {@link CandidateValidator} over a proposed entry set + Need.
 * Validity is a gate (returns {@link #isValid()}); scoring runs only after this returns true.
 * Required capabilities, required dependencies, refusal alignment and binding compatibility
 * are all gates here — never score dimensions.
 *
 * <p>Pattern: immutable <b>value object</b> aggregating the boolean gates plus the conflicts found.
 *
 * @param allRequiredCapabilitiesSatisfied whether all required capabilities are met.
 * @param versionConstraintsSatisfied      whether artifact version constraints are met.
 * @param requiredDependenciesResolved     whether required dependencies resolve.
 * @param refusalsAligned                  whether refusal expectations are satisfied.
 * @param bindingsCompatible               whether binding preferences are compatible.
 * @param conflicts                        the conflicts explaining any failed gate.
 */
public record CandidateValidity(
        boolean allRequiredCapabilitiesSatisfied,
        boolean versionConstraintsSatisfied,
        boolean requiredDependenciesResolved,
        boolean refusalsAligned,
        boolean bindingsCompatible,
        List<Conflict> conflicts
) {
    /** Compact constructor: defensively copies the conflicts list. */
    public CandidateValidity {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /**
     * @return true iff every gate passed (so scoring may proceed).
     */
    public boolean isValid() {
        return allRequiredCapabilitiesSatisfied
                && versionConstraintsSatisfied
                && requiredDependenciesResolved
                && refusalsAligned
                && bindingsCompatible;
    }
}
