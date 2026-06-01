package com.unfurl.fabric.advisor;

import java.util.Map;

public record LlmRequest(
        String purpose,
        String prompt,
        ThinkingEffort thinkingEffort,
        Map<String, String> metadata
) {
    public LlmRequest {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose is required");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        thinkingEffort = thinkingEffort == null ? ThinkingEffort.OFF : thinkingEffort;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public LlmRequest(String purpose, String prompt, Map<String, String> metadata) {
        this(purpose, prompt, ThinkingEffort.OFF, metadata);
    }
}
