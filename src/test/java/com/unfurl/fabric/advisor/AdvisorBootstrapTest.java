package com.unfurl.fabric.advisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvisorBootstrapTest {

    @Test
    void noConfigAnywhereReturnsNoopAdvisor() {
        AiAdvisor advisor = AdvisorBootstrap.resolve(
                new AdvisorBootstrap.BootstrapInputs(null, Map.of(), Map.of(), null));

        assertThat(advisor).isInstanceOf(NoopAiAdvisor.class);
    }

    @Test
    void cliFlagsTakePrecedenceOverEnvVars(@TempDir Path dir) throws IOException {
        AdvisorConfig config = AdvisorBootstrap.resolveConfig(
                new AdvisorBootstrap.BootstrapInputs(
                        null,
                        Map.of(AdvisorBootstrap.CliFlag.PROVIDER, "ollama",
                               AdvisorBootstrap.CliFlag.MODEL, "cli-model"),
                        Map.of(AdvisorBootstrap.EnvVar.PROVIDER, "anthropic",
                               AdvisorBootstrap.EnvVar.MODEL, "env-model"),
                        null));

        assertThat(config.providerName()).isEqualTo("ollama");
        assertThat(config.model()).isEqualTo("cli-model");
    }

    @Test
    void envVarsTakePrecedenceOverConfigFile(@TempDir Path dir) throws IOException {
        Path configFile = dir.resolve("advisor.yaml");
        Files.writeString(configFile, """
                provider: anthropic
                model: file-model
                """, StandardCharsets.UTF_8);

        AdvisorConfig config = AdvisorBootstrap.resolveConfig(
                new AdvisorBootstrap.BootstrapInputs(
                        configFile,
                        Map.of(),
                        Map.of(AdvisorBootstrap.EnvVar.PROVIDER, "ollama",
                               AdvisorBootstrap.EnvVar.MODEL, "env-model"),
                        null));

        assertThat(config.providerName()).isEqualTo("ollama");
        assertThat(config.model()).isEqualTo("env-model");
    }

    @Test
    void configFileSuppliesAllFieldsWhenNoOverridesPresent(@TempDir Path dir) throws IOException {
        Path configFile = dir.resolve("advisor.yaml");
        Files.writeString(configFile, """
                provider: anthropic
                model: claude-opus-4-7
                endpoint: https://api.anthropic.com/v1/messages
                apiKey: from-file
                effortOverrides:
                  rank-ambiguous-candidates: OFF
                providerOptions:
                  anthropic.budget_tokens: "16384"
                """, StandardCharsets.UTF_8);

        AdvisorConfig config = AdvisorBootstrap.resolveConfig(
                new AdvisorBootstrap.BootstrapInputs(configFile, Map.of(), Map.of(), null));

        assertThat(config.providerName()).isEqualTo("anthropic");
        assertThat(config.model()).isEqualTo("claude-opus-4-7");
        assertThat(config.endpoint()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(config.apiKey()).isEqualTo("from-file");
        assertThat(config.effortOverrides())
                .containsEntry("rank-ambiguous-candidates", ThinkingEffort.OFF);
        assertThat(config.providerOptions())
                .containsEntry("anthropic.budget_tokens", "16384");
    }

    @Test
    void providerNotOnClasspathThrowsClearError() {
        AdvisorBootstrap.BootstrapInputs inputs = new AdvisorBootstrap.BootstrapInputs(
                null,
                Map.of(AdvisorBootstrap.CliFlag.PROVIDER, "does-not-exist",
                       AdvisorBootstrap.CliFlag.MODEL, "any"),
                Map.of(),
                null);

        assertThatThrownBy(() -> AdvisorBootstrap.resolve(inputs))
                .isInstanceOf(AdvisorBootstrapException.class)
                .hasMessageContaining("does-not-exist")
                .hasMessageContaining("unfurl-fabric-advisor-does-not-exist");
    }

    @Test
    void providerOnClasspathResolvesViaServiceLoader() {
        // Uses the fake ProviderFactory registered in
        // src/test/resources/META-INF/services/com.unfurl.fabric.advisor.ProviderFactory
        AiAdvisor advisor = AdvisorBootstrap.resolve(new AdvisorBootstrap.BootstrapInputs(
                null,
                Map.of(AdvisorBootstrap.CliFlag.PROVIDER, "fake-test-provider",
                       AdvisorBootstrap.CliFlag.MODEL, "test-model"),
                Map.of(),
                null));

        assertThat(advisor).isInstanceOf(LlmAdvisor.class);
    }
}
