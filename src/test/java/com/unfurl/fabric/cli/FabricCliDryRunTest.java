package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDryRunTest {
    @Test
    void previewsExactCompileWithoutWritingOutputs(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(catalog, "storage.jar", "storage", "storage.put",
                "object-store@^1?substrate=true&provider=s3");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path contractOut = dir.resolve("contract.yaml");
        Path profileOut = dir.resolve("profile.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run("dry-run",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", contractOut.toString(),
                "--substrate-profile-out", profileOut.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("DRY_RUN")
                .contains("writesFiles=false")
                .contains("selectionMode=AUTO_SINGLE")
                .contains("substrateProfileHash=")
                .contains("Selections")
                .contains("com.unfurl:storage:1.0.0")
                .contains("Decision audit")
                .contains("scoreBreakdown:");
        assertThat(Files.exists(contractOut)).isFalse();
        assertThat(Files.exists(profileOut)).isFalse();
    }

    @Test
    void ambiguousDryRunRequiresExplicitSelection(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "a.jar", "storage-a", "storage.put");
        CliTestFixtures.writeCatalogJar(catalog, "b.jar", "storage-b", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun result = CliTestFixtures.run("dry-run",
                "--catalog", catalog.toString(),
                "--needs", needs.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr())
                .contains("ambiguous candidates:")
                .contains("ambiguous dry-run")
                .containsPattern("cand-[0-9a-f]{12}");
    }

    @Test
    void autoSelectBestDryRunPreviewsChosenCandidate(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "a.jar", "storage-a", "storage.put");
        CliTestFixtures.writeCatalogJar(catalog, "b.jar", "storage-b", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun result = CliTestFixtures.run("dry-run",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--auto-select-best");

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("DRY_RUN")
                .contains("selectionMode=AUTO_BEST_SCORE")
                .containsPattern("candidateId=cand-[0-9a-f]{12}");
    }
}
