package com.unfurl.fabric.needs;

import com.vdurmont.semver4j.Requirement;

/**
 * Semantic-version range for an <i>artifact</i>'s version (e.g. the JAR's coordinates).
 * Distinct from {@link CapabilityVersionRange}; the two version concepts never share a type.
 * Backed by semver4j with npm-style ranges.
 */
public record ArtifactVersionRange(String range) {

    public ArtifactVersionRange {
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("artifact version range cannot be blank");
        }
    }

    public static ArtifactVersionRange any() {
        return new ArtifactVersionRange("*");
    }

    public boolean satisfiedBy(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        try {
            return Requirement.buildNPM(range).isSatisfiedBy(version);
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
