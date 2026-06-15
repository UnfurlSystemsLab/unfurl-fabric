package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDeployPresetTest {

    @Test
    void deployLocalPresetEmitsThenApplies(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);
        Path out = dir.resolve("deploy");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "deploy",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--target", "local",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("targetKind=local-compose", "apply=ok", "status=applied");
        assertThat(Files.readString(out.resolve("apply-marker.txt"))).isEqualTo("local-compose");
    }
}
