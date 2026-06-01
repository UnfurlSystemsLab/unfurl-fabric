package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliListCapabilitiesTest {
    @Test
    void listsCapabilitiesForEveryCatalogEntry(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        CliTestFixtures.writeCatalogJar(catalog, "audit.jar", "audit-log", "audit.write");

        CliTestFixtures.CliRun result = CliTestFixtures.run("list-capabilities", "--catalog", catalog.toString());

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("com.unfurl:storage-s3:1.0.0 -> [storage.put:1.0.0]")
                .contains("com.unfurl:audit-log:1.0.0 -> [audit.write:1.0.0]");
    }
}
