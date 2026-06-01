package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliVerifyNoCatalogTest {
    @Test
    void verifiesSignatureOnlyWhenCatalogIsAbsent(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);

        CliTestFixtures.CliRun result = CliTestFixtures.run("verify", "--contract", paths.signed().toString(),
                "--trust-keys", paths.trustKeys().toString());

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("signatureOk=true")
                .contains("catalogDriftPresent=false")
                .contains("overallOk=true");
    }
}
