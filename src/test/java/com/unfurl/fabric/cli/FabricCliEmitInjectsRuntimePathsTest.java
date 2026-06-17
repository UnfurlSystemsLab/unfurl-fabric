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

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "emit",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--target", "local",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        String artifact = Files.readString(out.resolve("local-compose-artifact.txt"));
        assertThat(artifact)
                .contains("fabricContractPath=")
                .contains("substrateProfilePath=")
                .contains(paths.signed().toAbsolutePath().toString())
                .contains(paths.profile().toAbsolutePath().toString());
    }
}
