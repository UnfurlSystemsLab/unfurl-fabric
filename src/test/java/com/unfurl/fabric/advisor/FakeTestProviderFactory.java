package com.unfurl.fabric.advisor;

/**
 * Test-only ProviderFactory used by AdvisorBootstrapTest to prove ServiceLoader discovery
 * works without depending on any specific provider adapter module. Registered via
 * src/test/resources/META-INF/services/com.unfurl.fabric.advisor.ProviderFactory.
 */
public final class FakeTestProviderFactory implements ProviderFactory {

    @Override
    public String providerName() {
        return "fake-test-provider";
    }

    @Override
    public LlmProvider create(AdvisorConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        return new FakeProvider(config.model());
    }

    private record FakeProvider(String model) implements LlmProvider {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(
                    "fake response from " + model + " (purpose=" + request.purpose() + ")",
                    java.util.Map.of("provider", "fake-test-provider", "model", model));
        }
    }
}
