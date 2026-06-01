package com.unfurl.fabric.matcher;

import java.util.List;

/**
 * Result of running the {@link CandidateValidator} over a proposed entry set + Need.
 * Validity is a gate (returns {@link #isValid()}); scoring runs only after this returns true.
 * Required capabilities, required dependencies, refusal alignment and binding compatibility
 * are all gates here — never score dimensions.
 */
public record CandidateValidity(
        boolean allRequiredCapabilitiesSatisfied,
        boolean versionConstraintsSatisfied,
        boolean requiredDependenciesResolved,
        boolean refusalsAligned,
        boolean bindingsCompatible,
        List<Conflict> conflicts
) {
    public CandidateValidity {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public boolean isValid() {
        return allRequiredCapabilitiesSatisfied
                && versionConstraintsSatisfied
                && requiredDependenciesResolved
                && refusalsAligned
                && bindingsCompatible;
    }
}
