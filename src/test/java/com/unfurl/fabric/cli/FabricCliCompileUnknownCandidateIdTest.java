package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliCompileUnknownCandidateIdTest {
    @Test
    void unknownCandidateIdIsUsageErrorAndListsValidIds(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "a.jar", "storage-a", "storage.put");
        CliTestFixtures.writeCatalogJar(catalog, "b.jar", "storage-b", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--select", "cand-doesnotexist",
                "--out", dir.resolve("out.yaml").toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("unknown candidate id cand-doesnotexist")
                .containsPattern("cand-[0-9a-f]{12}");
    }
}
