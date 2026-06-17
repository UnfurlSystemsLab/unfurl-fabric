package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDeployStitchApplyTest {

    @Test
    void deployTargetsWithApplyAppliesEverySubDir(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSignMixedShapes(dir);
        Path out = dir.resolve("deploy");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "deploy",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--targets", "local,k8s-dev,remote",
                "--trust-keys", paths.trustKeys().toString(),
                "--apply",
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains(
                "stitchedApply=ok",
                "apply target=local-compose status=ok",
                "apply target=k8s status=ok",
                "apply target=remote status=ok");
        assertThat(Files.exists(out.resolve("local-compose").resolve("apply-marker.txt"))).isTrue();
        assertThat(Files.exists(out.resolve("k8s").resolve("apply-marker.txt"))).isTrue();
        assertThat(Files.exists(out.resolve("remote").resolve("apply-marker.txt"))).isTrue();
    }

    @Test
    void deployTargetsWithoutApplyStopsAtEmit(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSignMixedShapes(dir);
        Path out = dir.resolve("deploy");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "deploy",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--targets", "local,k8s-dev,remote",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("apply=skipped", "requires --apply");
        assertThat(Files.exists(out.resolve("local-compose").resolve("apply-marker.txt"))).isFalse();
    }
}
