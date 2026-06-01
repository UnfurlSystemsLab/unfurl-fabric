package com.unfurl.fabric.advisor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator-facing advisor configuration. The {@link AdvisorBootstrap} resolves this from
 * (in precedence order) CLI flags, environment variables, a YAML config file, then
 * built-in defaults. If no provider is named anywhere, the bootstrap returns a
 * {@link NoopAiAdvisor} — fabric stays fully offline-capable.
 *
 * <p>The {@code providerOptions} map carries provider-specific hints (e.g.
 * {@code ollama.temperature}, {@code anthropic.budget_tokens}) that the adapter consumes
 * via {@link LlmRequest#metadata()}. The {@code effortOverrides} map lets operators
 * change the per-purpose default effort that {@link LlmAdvisor} applies — keys are
 * purpose names like {@code "rank-ambiguous-candidates"} or {@code "suggest-substitutes-for-no-match"}.
 */
public record AdvisorConfig(
        String providerName,
        String model,
        String endpoint,
        String apiKey,
        Map<String, ThinkingEffort> effortOverrides,
        Map<String, String> providerOptions
) {
    public AdvisorConfig {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("provider name is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        effortOverrides = effortOverrides == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(effortOverrides));
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providerOptions));
    }
}
