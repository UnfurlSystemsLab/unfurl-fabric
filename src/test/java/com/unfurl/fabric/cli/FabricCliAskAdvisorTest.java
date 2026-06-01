package com.unfurl.fabric.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliAskAdvisorTest {

    @Test
    void noAdvisorConfigReturnsNoopExplanation(@TempDir Path dir) throws IOException {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun run = CliTestFixtures.run(
                "ask-advisor",
                "--catalog", catalog.toString(),
                "--needs", needs.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout())
                .contains("advisor=NoopAiAdvisor")
                .contains("providerInvoked=false")
                .contains("AI advisor disabled");
    }

    @Test
    void configuredAdvisorOnExactMatchSkipsProviderCall(@TempDir Path dir) throws IOException {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun run = CliTestFixtures.run(
                "ask-advisor",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--advisor-provider", "fake-test-provider",
                "--advisor-model", "ut-model");

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout())
                .contains("advisor=LlmAdvisor")
                // ExactMatch ⇒ LlmAdvisor returns AdvisorAdvice.none(), provider not invoked.
                .contains("providerInvoked=false")
                .contains("Exact match");
    }

    @Test
    void questionRoutesToExplainAndInvokesProvider(@TempDir Path dir) throws IOException {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun run = CliTestFixtures.run(
                "ask-advisor",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--advisor-provider", "fake-test-provider",
                "--advisor-model", "ut-model",
                "--question", "Why this selection?");

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout())
                .contains("advisor=LlmAdvisor")
                .contains("question=Why this selection?")
                .contains("providerInvoked=true")
                // Fake provider's response includes purpose=explain
                .contains("purpose=explain");
    }

    @Test
    void unknownProviderProducesClearError(@TempDir Path dir) throws IOException {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");

        CliTestFixtures.CliRun run = CliTestFixtures.run(
                "ask-advisor",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--advisor-provider", "definitely-not-real",
                "--advisor-model", "any");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stderr())
                .contains("definitely-not-real")
                .contains("unfurl-fabric-advisor-definitely-not-real");
    }

    @Test
    void configFileSuppliesAdvisorWhenNoCliFlagsGiven(@TempDir Path dir) throws IOException {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path advisorConfig = dir.resolve("advisor.yaml");
        Files.writeString(advisorConfig, """
                provider: fake-test-provider
                model: from-config-file
                """);

        CliTestFixtures.CliRun run = CliTestFixtures.run(
                "ask-advisor",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--advisor-config", advisorConfig.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).contains("advisor=LlmAdvisor");
    }
}
