package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliScanTest {
    @Test
    void scansCatalogAndWritesIndex(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path out = dir.resolve("catalog-index.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "scan", "--catalog", catalog.toString(), "--out", out.toString());

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).contains("entries=1").contains("skipped=0");
        assertThat(Files.readString(out)).contains("storage-s3").contains("storage.put");
    }
}
