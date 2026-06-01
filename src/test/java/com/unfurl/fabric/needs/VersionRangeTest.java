package com.unfurl.fabric.needs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionRangeTest {
    @Test
    void capabilityRangesAcceptNpmStyleExpressions() {
        CapabilityVersionRange range = new CapabilityVersionRange("^1");

        assertThat(range.satisfiedBy("1.2.3")).isTrue();
        assertThat(range.satisfiedBy("2.0.0")).isFalse();
        assertThat(range.satisfiedBy(null)).isFalse();
    }

    @Test
    void artifactRangesAreIndependentFromCapabilityRanges() {
        ArtifactVersionRange artifactRange = new ArtifactVersionRange(">=1.2.0 <2.0.0");
        ArtifactConstraint constraint = new ArtifactConstraint("com.unfurl", "storage-s3", artifactRange);

        assertThat(constraint.matches("com.unfurl:storage-s3:1.5.0")).isTrue();
        assertThat(constraint.matches("com.unfurl:storage-s3:2.0.0")).isFalse();
        assertThat(constraint.matches("com.other:storage-s3:1.5.0")).isFalse();
    }

    @Test
    void blankRangesAreRejectedAndMalformedVersionsDoNotSatisfy() {
        assertThatThrownBy(() -> new CapabilityVersionRange(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ArtifactVersionRange("^1").satisfiedBy("not-semver")).isFalse();
    }
}
