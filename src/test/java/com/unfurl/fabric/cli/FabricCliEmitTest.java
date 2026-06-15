package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliEmitTest {

    @Test
    void emitVerifiesSignedContractAndWritesManifest(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);
        Path out = dir.resolve("deploy");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "emit",
                "--contract", paths.signed().toString(),
                "--profile", paths.profile().toString(),
                "--target", "local",
                "--trust-keys", paths.trustKeys().toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("targetKind=local-compose", "artifacts=1", "emitManifestSha256=");
        assertThat(Files.exists(out.resolve("local-compose-artifact.txt"))).isTrue();
        assertThat(Files.exists(out.resolve("emit-manifest.json"))).isTrue();
    }
}
