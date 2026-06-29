package com.unfurl.fabric.matcher;

/**
 * Optional-impact diagnostics attached to an otherwise-valid CompositionCandidate.
 * Distinct from {@link Conflict}: warnings never invalidate a candidate; they only inform
 * scoring and explanation.
 *
 * <p>Pattern: <b>sealed interface as a sum type</b>; each permitted record is one warning variant.
 */
public sealed interface PlanningWarning
        permits PlanningWarning.OptionalCapabilityMissing,
                PlanningWarning.OptionalDependencyUnresolved,
                PlanningWarning.DeprecatedComponentSelected {

    /** @return the human-readable explanation of the warning. */
    String detail();

    /**
     * An optional capability had no provider in the candidate.
     *
     * @param capability the unmet optional capability.
     * @param detail     human-readable explanation.
     */
    record OptionalCapabilityMissing(String capability, String detail) implements PlanningWarning {
        /** Convenience constructor deriving the detail message. */
        public OptionalCapabilityMissing(String capability) {
            this(capability, "no provider satisfied optional capability " + capability);
        }
    }

    /**
     * An optional dependency could not be resolved.
     *
     * @param dependency the unresolved optional dependency.
     * @param detail     human-readable explanation.
     */
    record OptionalDependencyUnresolved(String dependency, String detail) implements PlanningWarning {
        /** Convenience constructor deriving the detail message. */
        public OptionalDependencyUnresolved(String dependency) {
            this(dependency, "optional dependency unresolved: " + dependency);
        }
    }

    /**
     * A DEPRECATED component was selected (permitted by trust policy, but worth flagging).
     *
     * @param coordinates the deprecated component's coordinates.
     * @param detail      human-readable explanation.
     */
    record DeprecatedComponentSelected(String coordinates, String detail) implements PlanningWarning {
        /** Convenience constructor deriving the detail message. */
        public DeprecatedComponentSelected(String coordinates) {
            this(coordinates, "selected component " + coordinates
                    + " is DEPRECATED (allowed by trust policy)");
        }
    }
}
