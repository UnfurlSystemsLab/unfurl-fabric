package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliVerifyTest {
    @Test
    void verifiesSignedContractWithCatalogAndFailsOnTamper(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths paths = CliTestFixtures.compileAndSign(dir);

        CliTestFixtures.CliRun ok = CliTestFixtures.run("verify", "--contract", paths.signed().toString(),
                "--trust-keys", paths.trustKeys().toString(), "--catalog", paths.catalog().toString());

        assertThat(ok.exitCode()).isEqualTo(0);
        assertThat(ok.stdout()).contains("signatureOk=true").contains("catalogClean=true").contains("overallOk=true");

        String tampered = Files.readString(paths.signed(), StandardCharsets.UTF_8)
                .replaceFirst("storage-s3", "storage-sx");
        Path tamperedPath = dir.resolve("tampered.yaml");
        Files.writeString(tamperedPath, tampered, StandardCharsets.UTF_8);

        CliTestFixtures.CliRun bad = CliTestFixtures.run("verify", "--contract", tamperedPath.toString(),
                "--trust-keys", paths.trustKeys().toString(), "--catalog", paths.catalog().toString());

        assertThat(bad.exitCode()).isEqualTo(1);
        assertThat(bad.stdout()).contains("signatureOk=false");
    }
}
