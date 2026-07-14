package com.unfurl.fabric.compiler;

import com.unfurl.dcp.contract.ContractValidator;
import com.unfurl.fabric.compile.ContractCompiler;
import com.unfurl.fabric.catalog.CatalogEntry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

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
        assertThat(first.childContracts()).hasSize(1);
        assertThat(first.contract().metadata().childContractIds())
                .containsExactly(first.childContracts().get(0).contractId());
        assertThat(contractTreeIsValid(first)).isTrue();
        assertThat(first.substrateProfileHash()).isNull();
        assertThat(first.signature()).isNull();
    }

    /**
     * Test helper: loads the parent and child contracts into the DCP tree validator.
     *
     * @param compiled compiled contract closure.
     * @return true when the aggregate parent references only loaded, acyclic children.
     */
    private static boolean contractTreeIsValid(CompiledContract compiled) {
        Map<URI, com.unfurl.dcp.contract.CompositionContract> contractsById = new LinkedHashMap<>();
        contractsById.put(compiled.contract().contractId(), compiled.contract());
        for (com.unfurl.dcp.contract.CompositionContract child : compiled.childContracts()) {
            contractsById.put(child.contractId(), child);
        }
        return new ContractValidator().validateTree(compiled.contract(), contractsById).valid();
    }
}
