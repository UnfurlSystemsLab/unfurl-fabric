package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliExplainRejectionTest {
    @Test
    void printsTrustRejectionReasons(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put", "BadCo", "ACTIVE");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path trust = CliTestFixtures.writeTrustPolicy(dir, "TrustedCo");

        CliTestFixtures.CliRun result = CliTestFixtures.run("explain-rejection",
                "--catalog", catalog.toString(), "--needs", needs.toString(), "--trust-policy", trust.toString());

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("com.unfurl:storage-s3:1.0.0")
                .contains("not in the trusted vendor list");
    }
}
