package com.unfurl.fabric.substrate;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubstrateProfileDeriverNoSecretsLeakTest {
    @Test
    void profileYamlContainsNoSecretLikeNames() {
        CatalogEntry entry = SubstrateDeriverFixtures.entry("storage", "object-store@^1?substrate=true");

        SubstrateProfile profile = new SubstrateProfileDeriver()
                .derive(SubstrateDeriverFixtures.candidate(entry));
        String yaml = new String(new SubstrateProfileCodec().canonicalBytes(profile), StandardCharsets.UTF_8);

        assertThat(yaml).doesNotContain("_KEY").doesNotContain("_TOKEN").doesNotContain("_SECRET");
    }

    @Test
    void secretLikeDependencyNamesAreRejectedBeforeProfileEmission() {
        CatalogEntry entry = SubstrateDeriverFixtures.entry("storage", "API_TOKEN@^1?substrate=true");

        assertThatThrownBy(() -> new SubstrateProfileDeriver().derive(SubstrateDeriverFixtures.candidate(entry)))
                .isInstanceOf(SubstrateProfileException.MalformedDependency.class)
                .hasMessageContaining("secret");
    }
}
