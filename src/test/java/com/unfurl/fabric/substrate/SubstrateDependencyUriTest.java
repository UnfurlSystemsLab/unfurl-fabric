package com.unfurl.fabric.substrate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubstrateDependencyUriTest {
    @Test
    void parsesSubstrateDependencyWithProviderHint() {
        SubstrateDependencyUri uri = SubstrateDependencyUri.parse(
                "object-store@^1?substrate=true&provider=s3");

        assertThat(uri.port()).isEqualTo("object-store");
        assertThat(uri.versionRange()).isEqualTo("^1");
        assertThat(uri.provider()).isEqualTo("s3");
    }

    @Test
    void parsesSubstrateDependencyWithoutProviderHint() {
        SubstrateDependencyUri uri = SubstrateDependencyUri.parse("queue@>=1.0.0 <2.0.0?substrate=true");

        assertThat(uri.port()).isEqualTo("queue");
        assertThat(uri.versionRange()).isEqualTo(">=1.0.0 <2.0.0");
        assertThat(uri.provider()).isNull();
    }

    @Test
    void rejectsMalformedAndNonSubstrateDependencies() {
        assertThatThrownBy(() -> SubstrateDependencyUri.parse("queue@^1"))
                .isInstanceOf(SubstrateProfileException.NotSubstrateDependency.class);
        assertThatThrownBy(() -> SubstrateDependencyUri.parse("queue?substrate=true"))
                .isInstanceOf(SubstrateProfileException.MalformedDependency.class);
        assertThatThrownBy(() -> SubstrateDependencyUri.parse("queue@^1?substrate=false"))
                .isInstanceOf(SubstrateProfileException.NotSubstrateDependency.class);
    }
}
