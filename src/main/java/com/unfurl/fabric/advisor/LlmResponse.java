package com.unfurl.fabric.advisor;

import java.util.Map;

public record LlmResponse(
        String text,
        Map<String, String> metadata
) {
    public LlmResponse {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
