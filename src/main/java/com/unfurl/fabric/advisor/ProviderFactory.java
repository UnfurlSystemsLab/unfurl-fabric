package com.unfurl.fabric.advisor;

/**
 * SPI implemented by each provider adapter (Ollama, Anthropic, OpenAI, Azure OpenAI, ...).
 * Adapters register their factory via {@code META-INF/services/com.unfurl.fabric.advisor.ProviderFactory}
 * so {@link AdvisorBootstrap} can discover them at runtime without fabric core importing
 * any specific adapter class.
 *
 * <p>This is the wedge-preserving mechanism that lets operators add or remove provider
 * adapters by adding or removing Maven modules — fabric core never depends on any
 * adapter at compile time.
 */
public interface ProviderFactory {

    /**
     * Stable provider identifier matched against {@link AdvisorConfig#providerName()}.
     * Convention: lowercase, hyphen-separated (e.g. {@code "ollama"}, {@code "azure-openai"}).
     */
    String providerName();

    /**
     * Builds a configured {@link LlmProvider}. Adapters interpret {@link AdvisorConfig#endpoint()},
     * {@link AdvisorConfig#apiKey()}, {@link AdvisorConfig#model()}, and
     * {@link AdvisorConfig#providerOptions()} according to their provider's protocol.
     */
    LlmProvider create(AdvisorConfig config);
}
