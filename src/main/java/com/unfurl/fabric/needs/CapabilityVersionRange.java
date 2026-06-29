package com.unfurl.fabric.needs;

import com.vdurmont.semver4j.Requirement;

/**
 * Semantic-version range for a <i>capability</i>'s version. Distinct from
 * {@link ArtifactVersionRange} so the data model never conflates "what semantic version of the
 * capability does this need to be?" with "what semantic version of the JAR is this?". Backed by
 * semver4j; accepts npm-style ranges (e.g. {@code ^1}, {@code >=1.2 <2.0}, {@code 1.x}).
 *
 * <p>Pattern: immutable <b>value object</b> wrapping a semver range string with a tolerant matcher.
 *
 * @param range the npm-style version range (non-blank).
 */
public record CapabilityVersionRange(String range) {

    /** Compact constructor: rejects a null/blank range. */
    public CapabilityVersionRange {
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("capability version range cannot be blank");
        }
    }

    /**
     * @return a range matching any version ({@code *}).
     */
    public static CapabilityVersionRange any() {
        return new CapabilityVersionRange("*");
    }

    /**
     * Test whether a concrete capability version satisfies this range.
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
