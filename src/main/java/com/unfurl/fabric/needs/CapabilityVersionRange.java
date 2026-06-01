package com.unfurl.fabric.needs;

import com.vdurmont.semver4j.Requirement;

/**
 * Semantic-version range for a <i>capability</i>'s version. Distinct from
 * {@link ArtifactVersionRange} so the data model never conflates "what semantic version of the
 * capability does this need to be?" with "what semantic version of the JAR is this?". Backed by
 * semver4j; accepts npm-style ranges (e.g. {@code ^1}, {@code >=1.2 <2.0}, {@code 1.x}).
 */
public record CapabilityVersionRange(String range) {

    public CapabilityVersionRange {
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("capability version range cannot be blank");
        }
    }

    public static CapabilityVersionRange any() {
        return new CapabilityVersionRange("*");
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
