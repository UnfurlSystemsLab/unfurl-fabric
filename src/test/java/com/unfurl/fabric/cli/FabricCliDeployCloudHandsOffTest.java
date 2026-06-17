package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDeployCloudHandsOffTest {

    @Test
    void deployCloudPresetStopsAtEmitUnlessApplyFlagIsPresent(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSignShape(dir, "CONTAINERIZED_SERVICE");
        Path out = dir.resolve("deploy");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "deploy",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--target", "aws",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("targetKind=k8s", "apply=skipped", "cloud target requires --apply");
        assertThat(Files.exists(out.resolve("k8s-artifact.txt"))).isTrue();
        assertThat(Files.exists(out.resolve("apply-marker.txt"))).isFalse();
    }
}
