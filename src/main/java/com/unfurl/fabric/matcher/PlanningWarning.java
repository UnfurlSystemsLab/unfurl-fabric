package com.unfurl.fabric.matcher;

/**
 * Optional-impact diagnostics attached to an otherwise-valid CompositionCandidate.
 * Distinct from {@link Conflict}: warnings never invalidate a candidate; they only inform
 * scoring and explanation.
 */
public sealed interface PlanningWarning
        permits PlanningWarning.OptionalCapabilityMissing,
                PlanningWarning.OptionalDependencyUnresolved,
                PlanningWarning.DeprecatedComponentSelected {

    String detail();

    record OptionalCapabilityMissing(String capability, String detail) implements PlanningWarning {
        public OptionalCapabilityMissing(String capability) {
            this(capability, "no provider satisfied optional capability " + capability);
        }
    }

    record OptionalDependencyUnresolved(String dependency, String detail) implements PlanningWarning {
        public OptionalDependencyUnresolved(String dependency) {
            this(dependency, "optional dependency unresolved: " + dependency);
        }
    }

    record DeprecatedComponentSelected(String coordinates, String detail) implements PlanningWarning {
        public DeprecatedComponentSelected(String coordinates) {
            this(coordinates, "selected component " + coordinates
                    + " is DEPRECATED (allowed by trust policy)");
        }
    }
}
