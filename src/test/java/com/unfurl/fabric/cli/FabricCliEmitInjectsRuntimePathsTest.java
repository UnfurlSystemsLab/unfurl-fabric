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
                .contains(workflows.toAbsolutePath().toString());
    }
}
