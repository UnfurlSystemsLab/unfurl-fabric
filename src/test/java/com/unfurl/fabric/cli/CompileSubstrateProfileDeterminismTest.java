package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompileSubstrateProfileDeterminismTest {
    @Test
    void sameInputProducesByteIdenticalProfileYaml(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(
                catalog, "storage.jar", "storage-s3", "storage.put",
                "queue@^1?substrate=true",
                "object-store@^1?substrate=true");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path firstContract = dir.resolve("first.yaml");
        Path secondContract = dir.resolve("second.yaml");
        Path firstProfile = dir.resolve("first-profile.yaml");
        Path secondProfile = dir.resolve("second-profile.yaml");

        CliTestFixtures.CliRun first = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", firstContract.toString(),
                "--substrate-profile-out", firstProfile.toString());
        CliTestFixtures.CliRun second = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", secondContract.toString(),
                "--substrate-profile-out", secondProfile.toString());
        assertThat(first.exitCode()).as(first.stderr()).isEqualTo(0);
        assertThat(second.exitCode()).as(second.stderr()).isEqualTo(0);

        assertThat(Files.readAllBytes(firstProfile)).isEqualTo(Files.readAllBytes(secondProfile));
    }
}
