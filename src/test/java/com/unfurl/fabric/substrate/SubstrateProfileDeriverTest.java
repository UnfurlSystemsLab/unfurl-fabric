package com.unfurl.fabric.substrate;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.substrate.api.BindingMode;
import com.unfurl.substrate.api.SubstrateProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubstrateProfileDeriverTest {
    @Test
    void derivesRequiredPortsFromSubstrateDependencies() {
        CatalogEntry entry = SubstrateDeriverFixtures.entry(
                "storage",
                "object-store@^1?substrate=true&provider=s3",
                "queue@>=1.0.0 <2.0.0?substrate=true");

        SubstrateProfile profile = new SubstrateProfileDeriver()
                .derive(SubstrateDeriverFixtures.candidate(entry));

        assertThat(profile.portRequirements()).hasSize(2);
        assertThat(profile.portRequirements()).extracting(p -> p.port())
                .containsExactly("object-store", "queue");
        assertThat(profile.portRequirements().get(0).constraints()).containsEntry("provider", "s3");
        assertThat(profile.metadata().supportedBindingModes()).containsExactly(BindingMode.IN_PROCESS);
        assertThat(profile.claims()).hasSize(1);
    }
}
