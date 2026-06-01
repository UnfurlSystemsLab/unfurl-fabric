package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliExplainTest {
    @Test
    void explainsSignedContractSelectionsAndAudit(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);

        CliTestFixtures.CliRun result = CliTestFixtures.run("explain",
                "--contract", paths.signed().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("Fabric contract explanation")
                .contains("canonicalHash=")
                .contains("signerKeyFingerprint=sha256:")
                .contains("substrateProfileHash=")
                .contains("selectionMode=AUTO_SINGLE")
                .contains("Selections")
                .contains("com.unfurl:storage-s3:1.0.0")
                .contains("artifactSha256=")
                .contains("claimHash=")
                .contains("bindingMode=IN_PROCESS")
                .contains("Decision path")
                .contains("scoreBreakdown:");
    }
}
