package com.unfurl.fabric.substrate;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.substrate.api.SubstrateProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubstrateProfileDeriverVersionUnionTest {
    @Test
    void overlappingRangesUseIntersection() {
        CatalogEntry first = SubstrateDeriverFixtures.entry("a", "queue@^1?substrate=true");
        CatalogEntry second = SubstrateDeriverFixtures.entry("b", "queue@>=1.0.0 <2.0.0?substrate=true");

        SubstrateProfile profile = new SubstrateProfileDeriver()
                .derive(SubstrateDeriverFixtures.candidate(first, second));

        assertThat(profile.portRequirements()).hasSize(1);
        assertThat(profile.portRequirements().get(0).versionRange()).isEqualTo("^1");
    }

    @Test
    void nonOverlappingRangesThrowConflict() {
        CatalogEntry first = SubstrateDeriverFixtures.entry("a", "queue@^1?substrate=true");
        CatalogEntry second = SubstrateDeriverFixtures.entry("b", "queue@^2?substrate=true");

        assertThatThrownBy(() -> new SubstrateProfileDeriver()
                .derive(SubstrateDeriverFixtures.candidate(first, second)))
                .isInstanceOf(SubstrateProfileException.ConflictingVersions.class)
                .hasMessageContaining("queue");
    }
}
