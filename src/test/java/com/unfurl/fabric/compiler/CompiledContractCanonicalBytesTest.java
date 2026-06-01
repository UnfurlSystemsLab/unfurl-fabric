package com.unfurl.fabric.compiler;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.testing.FabricTestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledContractCanonicalBytesTest {
    @Test
    void canonicalBytesAreSortedAndExcludeVolatilePaths() {
        ContractCompiler compiler = new ContractCompiler(CompilerFixtures.FIXED_CLOCK);
        CompiledContractCodec codec = new CompiledContractCodec();
        CatalogEntry b = FabricTestFixtures.entry("storage-z", "storage.put");
        CatalogEntry a = FabricTestFixtures.entry("storage-a", "storage.put");

        CompiledContract compiled = compiler.compile(
                CompilerFixtures.candidate(b, a), CompilerFixtures.storageNeed(), CompilerFixtures.HOST);
        String yaml = new String(codec.canonicalBytes(compiled), StandardCharsets.UTF_8);

        assertThat(yaml).doesNotContain("scannedAt");
        assertThat(yaml).doesNotContain("localPath");
        assertThat(yaml).doesNotContain("C:\\");
        assertThat(yaml.indexOf("com.unfurl:storage-a:1.0.0"))
                .isLessThan(yaml.indexOf("com.unfurl:storage-z:1.0.0"));
    }
}
