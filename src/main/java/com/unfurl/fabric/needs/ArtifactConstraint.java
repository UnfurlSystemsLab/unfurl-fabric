package com.unfurl.fabric.needs;

public record ArtifactConstraint(
        String group,
        String name,
        ArtifactVersionRange version
) {
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
