package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompileSubstrateProfileExcludesSecretsTest {
    @Test
    void profileOutputDoesNotContainSecretLikeNames(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithDependencies(
                catalog, "storage.jar", "storage-s3", "storage.put",
                "object-store@^1?substrate=true");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path contractOut = dir.resolve("contract.yaml");
        Path profileOut = dir.resolve("profile.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", contractOut.toString(),
                "--substrate-profile-out", profileOut.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        String profileYaml = Files.readString(profileOut);
        assertThat(profileYaml).doesNotContain("_KEY").doesNotContain("_TOKEN").doesNotContain("_SECRET");
    }
}
