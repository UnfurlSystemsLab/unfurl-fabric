package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDeployStitchTest {

    @Test
    void deployTargetsWritesStitchedTree(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSignMixedShapes(dir);
        Path out = dir.resolve("deploy");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "deploy",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--targets", "local,k8s-dev,remote",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains(
                "stitched=",
                "targetKind=local-compose",
                "targetKind=k8s",
                "targetKind=remote",
                "coverage=3",
                "stitchSha256=");
        assertThat(Files.exists(out.resolve("deploy-manifest.json"))).isTrue();
        assertThat(Files.exists(out.resolve("local-compose").resolve("emit-manifest.json"))).isTrue();
        assertThat(Files.exists(out.resolve("k8s").resolve("emit-manifest.json"))).isTrue();
        assertThat(Files.exists(out.resolve("remote").resolve("emit-manifest.json"))).isTrue();
    }

    @Test
    void deployTargetsFailsWhenShapeHasNoTarget(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSignShape(dir, "MANAGED_EXTERNAL_ADAPTER");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "deploy",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--targets", "local,k8s-dev",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", dir.resolve("deploy").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("coverage gap", "MANAGED_EXTERNAL_ADAPTER");
    }
}
