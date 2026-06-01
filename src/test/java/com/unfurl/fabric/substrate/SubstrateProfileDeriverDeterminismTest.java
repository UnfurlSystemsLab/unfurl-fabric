package com.unfurl.fabric.substrate;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubstrateProfileDeriverDeterminismTest {
    @Test
    void sameInputProducesByteIdenticalCanonicalProfileBytes() {
        CatalogEntry a = SubstrateDeriverFixtures.entry("a", "queue@^1?substrate=true");
        CatalogEntry b = SubstrateDeriverFixtures.entry("b", "object-store@^1?substrate=true");
        SubstrateProfileDeriver deriver = new SubstrateProfileDeriver();
        SubstrateProfileCodec codec = new SubstrateProfileCodec();

        byte[] first = codec.canonicalBytes(deriver.derive(SubstrateDeriverFixtures.candidate(b, a)));
        byte[] second = codec.canonicalBytes(deriver.derive(SubstrateDeriverFixtures.candidate(a, b)));

        assertThat(first).isEqualTo(second);
    }
}
