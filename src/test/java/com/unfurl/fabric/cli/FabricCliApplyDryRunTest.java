package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliApplyDryRunTest {

    @Test
    void applyDryRunPrintsResourcesAndDoesNotMutatePlanDirectory(@TempDir Path dir) throws Exception {
        Path plan = dir.resolve("plan");
        Files.createDirectories(plan);

        CliTestFixtures.CliRun result = CliTestFixtures.run(
                "apply",
                "--plan", plan.toString(),
                "--target", "local",
                "--dry-run");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("apply=ok", "dryRun=true", "status=dry-run");
        assertThat(Files.exists(plan.resolve("apply-marker.txt"))).isFalse();
    }
}
