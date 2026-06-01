package com.unfurl.fabric.compiler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledContractCodecTest {
    @Test
    void roundTripsYaml() {
        CompiledContractCodec codec = new CompiledContractCodec();
        CompiledContract compiled = CompilerFixtures.compiled();

        CompiledContract parsed = codec.parse(codec.write(compiled));

        assertThat(parsed.contract().contractId()).isEqualTo(compiled.contract().contractId());
        assertThat(parsed.contract().binding()).isEqualTo(compiled.contract().binding());
        assertThat(parsed.selections()).isEqualTo(compiled.selections());
        assertThat(parsed.audit().scoreBreakdown()).isEqualTo(compiled.audit().scoreBreakdown());
        assertThat(parsed.substrateProfileHash()).isNull();
        assertThat(parsed.signature()).isNull();
    }
}
