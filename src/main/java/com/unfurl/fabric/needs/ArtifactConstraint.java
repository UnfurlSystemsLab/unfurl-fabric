package com.unfurl.fabric.needs;

/**
 * An operator constraint pinning a need to a specific artifact (group + name) at a version range.
 *
 * <p>Pattern: immutable <b>specification</b> value object — {@link #matches(String)} tests Maven-style
 * coordinates against this constraint.
 *
 * @param group   the artifact group (required).
 * @param name    the artifact name (required).
 * @param version the acceptable artifact version range (defaults to any).
 */
public record ArtifactConstraint(
        String group,
        String name,
        ArtifactVersionRange version
) {
    /** Compact constructor: requires group and name; defaults a null version range to "any". */
    public ArtifactConstraint {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("artifact group is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("artifact name is required");
        }
        if (version == null) {
            version = ArtifactVersionRange.any();
        }
    }

    /**
     * Returns true when the given Maven-style coordinates "group:name:version" satisfy this
     * constraint. The constraint matches by group + name; the artifact version (the third
     * component of the coordinates) is checked against {@link ArtifactVersionRange}.
     *
     * @param coordinates Maven-style {@code group:name:version} coordinates.
     * @return true iff group and name match and the version satisfies the range.
     */
    public boolean matches(String coordinates) {
        if (coordinates == null) {
            return false;
        }
        String[] parts = coordinates.split(":");
        if (parts.length != 3) {
            return false;
        }
        return group.equals(parts[0])
                && name.equals(parts[1])
                && version.satisfiedBy(parts[2]);
    }
}
