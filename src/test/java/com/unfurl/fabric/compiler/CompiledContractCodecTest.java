package com.unfurl.fabric.compiler;

import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
        assertThat(parsed.bindingPlan()).isNull();
        assertThat(parsed.signature()).isNull();
    }

    @Test
    void roundTripsInlineBindingPlan() {
        CompiledContractCodec codec = new CompiledContractCodec();
        CompiledContract compiled = CompilerFixtures.compiled();
        BindingPlan plan = new BindingPlan(List.of(new BindingPlanEntry(
                "storage-s3",
                "storage.put",
                "com.unfurl:storage-s3:1.0.0",
                compiled.selections().get(0).artifact().sha256(),
                DeploymentShape.IN_PROCESS_LIBRARY,
                BindingMode.IN_PROCESS,
                Optional.empty(),
                List.of())));

        CompiledContract withPlan = new CompiledContract(
                compiled.contract(),
                compiled.selections(),
                compiled.audit(),
                compiled.substrateProfileHash(),
                plan,
                compiled.signature());

        CompiledContract parsed = codec.parse(codec.write(withPlan));

        assertThat(parsed.bindingPlan()).isEqualTo(plan);
    }
}
