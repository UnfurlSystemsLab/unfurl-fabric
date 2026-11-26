package com.unfurl.fabric.deploy;

import com.unfurl.deployment.policy.DeploymentTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmitTargetInputs}: the per-kind rules {@code fabric deploy} and Studio's emit-plan preview share, and the target
 * file's own options winning over threaded paths.
 */
class EmitTargetInputsTest {
    private static final Path DIR = Path.of("handoff").toAbsolutePath();

    /** Local compose receives every path, written absolute, and its own options win. */
    @Test
    void localComposeReceivesEveryPathAndKeepsItsOwnOptions() {
        DeploymentTarget built = everything(target("local-compose", Map.of("trustKeysPath", "/operator/keys"))).build();

        assertThat(built.options())
                .containsEntry("fabricContractPath", DIR.resolve("contract.yaml").toString())
                .containsEntry("substrateProfilePath", DIR.resolve("profile.yaml").toString())
                .containsEntry("runtimeBindingPath", DIR.resolve("bindings.yaml").toString())
                .containsEntry("dcpRuntimeBundlePath", DIR.resolve("bundle.zip").toString())
                .containsEntry("flowWorkflowsPath", DIR.resolve("workflows").toString())
                .containsEntry("foundryDeploymentRootPath", DIR.resolve("foundry").toString())
                .containsEntry("componentArtifactsPath", DIR.resolve("jars").toString())
                .containsEntry("flowConfigPath", DIR.resolve("flow-config").toString())
                .containsEntry("flowSecretsPath", DIR.resolve("flow-secrets").toString())
                .containsEntry("serviceConfigPath.catalog", "/mounts/catalog-config")
                .containsEntry("trustKeysPath", "/operator/keys");
        assertThat(built.environment()).isEqualTo("local");
    }

    /** k8s receives only the composition-part inputs; it cannot deliver operator mounts. */
    @Test
    void k8sReceivesOnlyTheCompositionPartInputs() {
        DeploymentTarget built = everything(target("k8s", Map.of())).build();

        assertThat(built.options()).containsOnlyKeys("runtimeBindingPath", "dcpRuntimeBundlePath", "trustKeysPath");
    }

    /** Another kind is returned unchanged, and an unset path is not threaded. */
    @Test
    void otherKindsAreUnchangedAndUnsetPathsAreLeftOut() {
        DeploymentTarget remote = target("remote", Map.of("endpointRef", "config://remote"));
        assertThat(everything(remote).build()).isSameAs(remote);
        assertThat(EmitTargetInputs.forTarget(target("local-compose", Map.of())).contract(DIR.resolve("contract.yaml"))
                .build().options()).containsOnlyKeys("fabricContractPath");
    }

    private static EmitTargetInputs everything(DeploymentTarget target) {
        return EmitTargetInputs.forTarget(target)
                .contract(DIR.resolve("contract.yaml"))
                .profile(DIR.resolve("profile.yaml"))
                .runtimeBinding(DIR.resolve("bindings.yaml"))
                .dcpRuntimeBundle(DIR.resolve("bundle.zip"))
                .flowWorkflows(DIR.resolve("workflows"))
                .foundryDeploymentRoot(DIR.resolve("foundry"))
                .componentArtifacts(DIR.resolve("jars"))
                .trustKeys(DIR.resolve("keys"))
                .flowConfig(DIR.resolve("flow-config"))
                .flowSecrets(DIR.resolve("flow-secrets"))
                .serviceMounts(Map.of("serviceConfigPath.catalog", "/mounts/catalog-config"));
    }

    private static DeploymentTarget target(String kind, Map<String, String> options) {
        return new DeploymentTarget(kind, "local", Optional.empty(), Optional.empty(), options, List.of());
    }
}
