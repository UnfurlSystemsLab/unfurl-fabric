package com.unfurl.fabric.matcher;

/**
 * Structured description of why a candidate composition was rejected as invalid.
 * Carries enough detail for {@code fabric explain} and {@code fabric explain-rejection}
 * to surface the root cause to operators.
 *
 * <p>Pattern: <b>sealed interface as a sum type</b>; each permitted record is one conflict variant with
 * a self-describing {@code detail}.
 */
public sealed interface Conflict
        permits Conflict.VersionConflict,
                Conflict.OfferDetailConflict,
                Conflict.ArtifactConflict,
                Conflict.RefusalConflict,
                Conflict.BindingConflict,
                Conflict.DependencyConflict {

    /** @return the human-readable explanation of the conflict. */
    String detail();

    /**
     * No offer of a required capability satisfied the requested version range.
     *
     * @param capability       the required capability.
     * @param requestedRange   the requested version range.
     * @param availableVersion the version actually seen (or {@code <none>}).
     * @param detail           human-readable explanation.
     */
    record VersionConflict(String capability, String requestedRange, String availableVersion, String detail)
            implements Conflict {
        /** Convenience constructor deriving the detail message. */
        public VersionConflict(String capability, String requestedRange, String availableVersion) {
            this(capability, requestedRange, availableVersion,
                    "no offer of capability " + capability + " satisfies range " + requestedRange
                            + " (saw version " + availableVersion + ")");
        }
    }

    /**
     * A required capability version exists, but its DCP offer details do not satisfy the need.
     *
     * @param capability      the required capability.
     * @param requestedRange  the requested version range.
     * @param requiredDetails the DCP offer-detail subset that was required.
     * @param detail          human-readable explanation.
     */
    record OfferDetailConflict(
            String capability,
            String requestedRange,
            java.util.Map<String, Object> requiredDetails,
            String detail
    ) implements Conflict {
        /** Convenience constructor deriving the detail message. */
        public OfferDetailConflict(
                String capability,
                String requestedRange,
                java.util.Map<String, Object> requiredDetails
        ) {
            this(capability, requestedRange, requiredDetails,
                    "no offer of capability " + capability + " satisfies required DCP offer details "
                            + requiredDetails + " within range " + requestedRange);
        }
    }

    /**
     * No selected artifact matched a required artifact constraint.
     *
     * @param group          the constraint's group.
     * @param name           the constraint's name.
     * @param requestedRange the constraint's version range.
     * @param detail         human-readable explanation.
     */
    record ArtifactConflict(String group, String name, String requestedRange, String detail)
            implements Conflict {
        /** Convenience constructor deriving the detail message. */
        public ArtifactConflict(String group, String name, String requestedRange) {
            this(group, name, requestedRange,
                    "no selected artifact " + group + ":" + name + " satisfies range " + requestedRange);
        }
    }

    /**
     * A required concern was not owned by any selected component.
     *
     * @param concern the required concern.
     * @param detail  human-readable explanation.
     */
    record RefusalConflict(String concern, String detail) implements Conflict {
        /** Convenience constructor deriving the detail message. */
        public RefusalConflict(String concern) {
            this(concern, "needs require concern '" + concern + "' but no selected component owns it");
        }
    }

    /**
     * A preferred binding mode was incompatible with the provider's supported modes.
     *
     * @param capability the capability whose binding conflicts.
     * @param preferred  the preferred binding mode.
     * @param available  the provider's supported modes.
     * @param detail     human-readable explanation.
     */
    record BindingConflict(String capability, String preferred, String available, String detail)
            implements Conflict {
        /** Convenience constructor deriving the detail message. */
        public BindingConflict(String capability, String preferred, String available) {
            this(capability, preferred, available,
                    "binding preference " + preferred + " for capability " + capability
                            + " incompatible with available " + available);
        }
    }

    /**
     * A required dependency could not be resolved within the selected set.
     *
     * @param dependency the unresolved dependency string.
     * @param detail     human-readable explanation.
     */
    record DependencyConflict(String dependency, String detail) implements Conflict {
        /** Convenience constructor deriving the detail message. */
        public DependencyConflict(String dependency) {
            this(dependency, "required dependency unresolved: " + dependency);
        }
    }
}
