package com.unfurl.fabric.compiler;

import com.unfurl.dcp.contract.ContractValidator;
import com.unfurl.fabric.catalog.CatalogEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractCompilerTest {
    @Test
    void sameCandidateAndNeedProduceByteIdenticalCompiledContractBytes() {
        ContractCompiler compiler = new ContractCompiler(CompilerFixtures.FIXED_CLOCK);
        CompiledContractCodec codec = new CompiledContractCodec();
        CatalogEntry entry = CompilerFixtures.storageEntry();

        CompiledContract first = compiler.compile(
                CompilerFixtures.candidate(entry), CompilerFixtures.storageNeed(), CompilerFixtures.HOST);
        CompiledContract second = compiler.compile(
                CompilerFixtures.candidate(entry), CompilerFixtures.storageNeed(), CompilerFixtures.HOST);

        assertThat(codec.canonicalBytes(first)).isEqualTo(codec.canonicalBytes(second));
        assertThat(new ContractValidator().validate(first.contract()).valid()).isTrue();
        assertThat(first.substrateProfileHash()).isNull();
        assertThat(first.signature()).isNull();
    }
}
