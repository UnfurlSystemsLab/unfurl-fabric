package com.unfurl.fabric.matcher;

/**
 * Structured description of why a candidate composition was rejected as invalid.
 * Carries enough detail for {@code fabric explain} and {@code fabric explain-rejection}
 * to surface the root cause to operators.
 */
public sealed interface Conflict
        permits Conflict.VersionConflict,
                Conflict.ArtifactConflict,
                Conflict.RefusalConflict,
                Conflict.BindingConflict,
                Conflict.DependencyConflict {

    String detail();

    record VersionConflict(String capability, String requestedRange, String availableVersion, String detail)
            implements Conflict {
        public VersionConflict(String capability, String requestedRange, String availableVersion) {
            this(capability, requestedRange, availableVersion,
                    "no offer of capability " + capability + " satisfies range " + requestedRange
                            + " (saw version " + availableVersion + ")");
        }
    }

    record ArtifactConflict(String group, String name, String requestedRange, String detail)
            implements Conflict {
        public ArtifactConflict(String group, String name, String requestedRange) {
            this(group, name, requestedRange,
                    "no selected artifact " + group + ":" + name + " satisfies range " + requestedRange);
        }
    }

    record RefusalConflict(String concern, String detail) implements Conflict {
        public RefusalConflict(String concern) {
            this(concern, "needs require concern '" + concern + "' but no selected component owns it");
        }
    }

    record BindingConflict(String capability, String preferred, String available, String detail)
            implements Conflict {
        public BindingConflict(String capability, String preferred, String available) {
            this(capability, preferred, available,
                    "binding preference " + preferred + " for capability " + capability
                            + " incompatible with available " + available);
        }
    }

    record DependencyConflict(String dependency, String detail) implements Conflict {
        public DependencyConflict(String dependency) {
            this(dependency, "required dependency unresolved: " + dependency);
        }
    }
}
