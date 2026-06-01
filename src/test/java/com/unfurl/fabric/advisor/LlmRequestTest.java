package com.unfurl.fabric.advisor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestTest {
    @Test
    void threeArgumentConstructorDefaultsThinkingEffortOff() {
        LlmRequest request = new LlmRequest("rank", "prompt", Map.of());

        assertThat(request.thinkingEffort()).isEqualTo(ThinkingEffort.OFF);
    }

    @Test
    void nullThinkingEffortDefaultsOff() {
        LlmRequest request = new LlmRequest("rank", "prompt", null, Map.of("k", "v"));

        assertThat(request.thinkingEffort()).isEqualTo(ThinkingEffort.OFF);
        assertThat(request.metadata()).containsEntry("k", "v");
    }
}
