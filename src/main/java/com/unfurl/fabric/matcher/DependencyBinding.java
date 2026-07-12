package com.unfurl.fabric.matcher;

/**
 * Records how one declared dependency of a selected component is satisfied: by which provider, and
 * whether it is bound outside the selected peer catalog graph rather than another catalog component.
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param requirement        the dependency requirement string (required).
 * @param providerCoordinates coordinates of the providing artifact, or null when host-bound/unresolved.
 * @param hostBound          true if runtime/profile binding supplies this dependency (not a catalog component).
 */
public record DependencyBinding(
        String requirement,
        String providerCoordinates,
        boolean hostBound
) {
    /** Compact constructor: requires a non-blank requirement. */
    public DependencyBinding {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("dependency requirement is required");
        }
    }
}
