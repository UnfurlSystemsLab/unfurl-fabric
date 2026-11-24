package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2: the local-compose CLI emit must thread the signed-contract and
 * substrate-profile paths into the target so the compose backend produces a
 * STRICT (closed-world) runtime profile rather than LEGACY_OPEN_WORLD.
 */
class FabricCliEmitInjectsRuntimePathsTest {

    @Test
    void localEmitInjectsContractAndProfilePaths(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);
        Path out = dir.resolve("deploy");
        Path runtimeBinding = Files.writeString(dir.resolve("runtime-binding.yaml"), "runtime_binding_set: {}\n");
        Path runtimeBundle = Files.writeString(dir.resolve("dcp-runtime-bundle.zip"), "zip-bytes");
        Path workflows = Files.createDirectories(dir.resolve("workflows"));
        Files.writeString(workflows.resolve("workflow.yaml"), "id: wf\n");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "emit",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--runtime-binding", runtimeBinding.toString(),
                "--dcp-runtime-bundle", runtimeBundle.toString(),
                "--flow-workflows", workflows.toString(),
                "--target", "local",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        String artifact = Files.readString(out.resolve("local-compose-artifact.txt"));
        assertThat(artifact)
                .contains("fabricContractPath=")
                .contains("substrateProfilePath=")
                .contains("runtimeBindingPath=")
                .contains("dcpRuntimeBundlePath=")
                .contains("flowWorkflowsPath=")
                .contains(paths.signed().toAbsolutePath().toString())
                .contains(paths.profile().toAbsolutePath().toString())
                .contains(runtimeBinding.toAbsolutePath().toString())
                .contains(runtimeBundle.toAbsolutePath().toString())
                .contains(workflows.toAbsolutePath().toString())
                .doesNotContain("componentArtifactsPath=");
    }

    /**
     * {@code --flow-config} and {@code --flow-secrets} become {@code flowConfigPath} and {@code flowSecretsPath}, the
     * operator mounts Flow needs for an identity binding (emitter HLD-E §4.0a).
     */
    @Test
    void localEmitInjectsFlowIdentityMountPaths(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);
        Path out = dir.resolve("deploy");
        Path config = Files.createDirectories(dir.resolve("flow-config"));
        Path secrets = Files.createDirectories(dir.resolve("flow-secrets"));

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "emit",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--flow-config", config.toString(),
                "--flow-secrets", secrets.toString(),
                "--target", "local",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(Files.readString(out.resolve("local-compose-artifact.txt")))
                .contains("flowConfigPath=" + config.toAbsolutePath())
                .contains("flowSecretsPath=" + secrets.toAbsolutePath());
    }

    /**
     * {@code --service-config} and {@code --service-secrets} (repeatable, or comma-separated) become
     * {@code serviceConfigPath.<name>} and {@code serviceSecretsPath.<name>}, absolute, for local compose (emitter HLD-E
     * §4.0d, <em>Operator mounts</em>); whether a service may have them is the emitter's decision.
     */
    @Test
    void localEmitInjectsServiceMountPaths(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);
        Path out = dir.resolve("deploy");
        Path config = Files.createDirectories(dir.resolve("catalog-config"));
        Path secrets = Files.createDirectories(dir.resolve("catalog-secrets"));
        Path ledger = Files.createDirectories(dir.resolve("ledger-config"));

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "emit",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--service-config", "api-catalog=" + config + ",ledger=" + ledger,
                "--service-secrets", "api-catalog=" + secrets,
                "--target", "local",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(Files.readString(out.resolve("local-compose-artifact.txt")))
                .contains("serviceConfigPath.api-catalog=" + config.toAbsolutePath())
                .contains("serviceConfigPath.ledger=" + ledger.toAbsolutePath())
                .contains("serviceSecretsPath.api-catalog=" + secrets.toAbsolutePath());
    }

    /** A malformed mount, a name that is not a service name, and a service named twice for one flag are usage errors. */
    @Test
    void malformedServiceMountsAreUsageErrors(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);
        for (String[] mounts : new String[][]{{"--service-config", "api-catalog"}, {"--service-secrets", "API=dir"},
                {"--service-config", "a=x,a=y"}}) {
            CliTestFixtures.CliRun result = CliTestFixtures.run("emit", "--contract", paths.signed().toString(),
                    "--profile", paths.profile().toString(), mounts[0], mounts[1], "--target", "local",
                    "--trust-keys", paths.trustKeys().toString(), "--out", dir.resolve("deploy").toString());
            assertThat(result.exitCode()).as(String.join(" ", mounts)).isEqualTo(2);
            assertThat(result.stderr()).contains("service mounts:");
        }
    }

    /**
     * A {@code k8s} target receives the composition-part inputs (its declared parts need them) and never the operator
     * mounts, which it cannot deliver (HLD-api-documentation-artifacts §4.1).
     */
    @Test
    void k8sTargetReceivesCompositionPartInputsOnly(@TempDir Path dir) {
        Path binding = dir.resolve("runtime-binding.yaml");
        Path bundle = dir.resolve("bundle.zip");
        Path keys = dir.resolve("trust");
        java.util.Map<String, String> mounts = ServiceMountOptions.parse(java.util.List.of("api-catalog=" + dir), java.util.List.of());

        var k8s = DeployCliSupport.withRuntimePaths(DeployCliSupport.loadTarget("k8s-dev"), dir.resolve("c.yaml"),
                dir.resolve("p.yaml"), binding, bundle, dir.resolve("wf"), dir.resolve("foundry"), null, keys,
                dir.resolve("fc"), dir.resolve("fs"), mounts);
        assertThat(k8s.options())
                .containsEntry("runtimeBindingPath", binding.toAbsolutePath().toString())
                .containsEntry("dcpRuntimeBundlePath", bundle.toAbsolutePath().toString())
                .containsEntry("trustKeysPath", keys.toAbsolutePath().toString())
                .containsEntry("rendering", "both")
                .doesNotContainKeys("serviceConfigPath.api-catalog", "fabricContractPath", "flowConfigPath", "flowWorkflowsPath");

        var local = DeployCliSupport.withRuntimePaths(DeployCliSupport.loadTarget("local"), dir.resolve("c.yaml"),
                dir.resolve("p.yaml"), binding, bundle, null, null, null, keys, null, null, mounts);
        assertThat(local.options()).containsEntry("serviceConfigPath.api-catalog", dir.toAbsolutePath().normalize().toString());
    }

    /** {@code --catalog} feeds only the admitted-component overlay, so a plan that hosts nothing refuses it. */
    @Test
    void refusesACatalogForAPlanThatHostsNothing(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);

        CliTestFixtures.CliRun result = CliTestFixtures.run("emit", "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(), "--target", "local", "--trust-keys", paths.trustKeys().toString(),
                "--out", dir.resolve("deploy").toString(), "--catalog", dir.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("--catalog is read for hosted in-process components, and the signed plan has none");
    }
}
