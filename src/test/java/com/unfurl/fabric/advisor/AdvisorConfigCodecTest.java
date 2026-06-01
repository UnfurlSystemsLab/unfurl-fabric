package com.unfurl.fabric.advisor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvisorConfigCodecTest {

    private final AdvisorConfigCodec codec = new AdvisorConfigCodec();

    @Test
    void parsesMinimalConfigWithJustProviderAndModel() {
        byte[] yaml = """
                provider: ollama
                model: my-org/composition-advisor:1.0
                """.getBytes(StandardCharsets.UTF_8);

        AdvisorConfig config = codec.parse(yaml);

        assertThat(config.providerName()).isEqualTo("ollama");
        assertThat(config.model()).isEqualTo("my-org/composition-advisor:1.0");
        assertThat(config.endpoint()).isNull();
        assertThat(config.apiKey()).isNull();
        assertThat(config.effortOverrides()).isEmpty();
        assertThat(config.providerOptions()).isEmpty();
    }

    @Test
    void parsesFullConfigWithEffortOverridesAndProviderOptions() {
        byte[] yaml = """
                provider: anthropic
                model: claude-opus-4-7
                endpoint: https://api.anthropic.com/v1/messages
                apiKey: sk-test
                effortOverrides:
                  rank-ambiguous-candidates: OFF
                  suggest-substitutes-for-no-match: HIGH
                providerOptions:
                  anthropic.budget_tokens: "16384"
                """.getBytes(StandardCharsets.UTF_8);

        AdvisorConfig config = codec.parse(yaml);

        assertThat(config.providerName()).isEqualTo("anthropic");
        assertThat(config.endpoint()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(config.apiKey()).isEqualTo("sk-test");
        assertThat(config.effortOverrides()).containsEntry(
                "rank-ambiguous-candidates", ThinkingEffort.OFF);
        assertThat(config.effortOverrides()).containsEntry(
                "suggest-substitutes-for-no-match", ThinkingEffort.HIGH);
        assertThat(config.providerOptions()).containsEntry(
                "anthropic.budget_tokens", "16384");
    }

    @Test
    void rejectsMissingProvider() {
        byte[] yaml = "model: foo\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.parse(yaml))
                .isInstanceOf(AdvisorBootstrapException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void rejectsMissingModel() {
        byte[] yaml = "provider: ollama\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.parse(yaml))
                .isInstanceOf(AdvisorBootstrapException.class)
                .hasMessageContaining("model");
    }

    @Test
    void rejectsInvalidEffortOverride() {
        byte[] yaml = """
                provider: ollama
                model: foo
                effortOverrides:
                  rank-ambiguous-candidates: NEXT_LEVEL
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.parse(yaml))
                .isInstanceOf(AdvisorBootstrapException.class)
                .hasMessageContaining("NEXT_LEVEL")
                .hasMessageContaining("OFF/LOW/MEDIUM/HIGH");
    }

    @Test
    void roundTripPreservesAllFields() {
        AdvisorConfig original = new AdvisorConfig(
                "ollama",
                "my-org/composition-advisor:1.0",
                "http://localhost:11434/api/generate",
                null,
                Map.of("rank-ambiguous-candidates", ThinkingEffort.LOW),
                Map.of("ollama.temperature", "0.0"));

        byte[] yaml;
        try {
            yaml = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .writeValueAsBytes(toRoundTripMap(original));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        AdvisorConfig roundTripped = codec.parse(yaml);

        assertThat(roundTripped).isEqualTo(original);
    }

    private Map<String, Object> toRoundTripMap(AdvisorConfig config) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("provider", config.providerName());
        map.put("model", config.model());
        if (config.endpoint() != null) {
            map.put("endpoint", config.endpoint());
        }
        java.util.LinkedHashMap<String, String> efforts = new java.util.LinkedHashMap<>();
        config.effortOverrides().forEach((k, v) -> efforts.put(k, v.name()));
        if (!efforts.isEmpty()) {
            map.put("effortOverrides", efforts);
        }
        if (!config.providerOptions().isEmpty()) {
            map.put("providerOptions", config.providerOptions());
        }
        return map;
    }
}
