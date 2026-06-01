package com.unfurl.fabric.compiler;

import com.unfurl.fabric.catalog.CatalogEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactProvenanceTest {
    @Test
    void artifactShaAndClaimHashAreStableAcrossRecompileCycles() {
        ContractCompiler compiler = new ContractCompiler(CompilerFixtures.FIXED_CLOCK);
        CatalogEntry entry = CompilerFixtures.storageEntry();

        SelectionRecord first = compiler.compile(
                CompilerFixtures.candidate(entry), CompilerFixtures.storageNeed(), CompilerFixtures.HOST)
                .selections().get(0);
        SelectionRecord second = compiler.compile(
                CompilerFixtures.candidate(entry), CompilerFixtures.storageNeed(), CompilerFixtures.HOST)
                .selections().get(0);

        assertThat(first.artifact().sha256()).isEqualTo(second.artifact().sha256());
        assertThat(first.claimHash()).isEqualTo(second.claimHash());
        assertThat(first.artifact().coordinates()).isEqualTo(second.artifact().coordinates());
    }
}
