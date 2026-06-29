package com.unfurl.fabric.needs;

import com.vdurmont.semver4j.Requirement;

/**
 * Semantic-version range for an <i>artifact</i>'s version (e.g. the JAR's coordinates).
 * Distinct from {@link CapabilityVersionRange}; the two version concepts never share a type.
 * Backed by semver4j with npm-style ranges.
 *
 * <p>Pattern: immutable <b>value object</b> wrapping a semver range string with a tolerant matcher.
 *
 * @param range the npm-style version range (non-blank).
 */
public record ArtifactVersionRange(String range) {

    /** Compact constructor: rejects a null/blank range. */
    public ArtifactVersionRange {
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("artifact version range cannot be blank");
        }
    }

    /**
     * @return a range matching any version ({@code *}).
     */
    public static ArtifactVersionRange any() {
        return new ArtifactVersionRange("*");
    }

    /**
     * Test whether a concrete version satisfies this range.
     *
     * @param version the concrete version string.
     * @return true iff non-blank and satisfied; false on blank input or an unparseable range/version.
     */
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
