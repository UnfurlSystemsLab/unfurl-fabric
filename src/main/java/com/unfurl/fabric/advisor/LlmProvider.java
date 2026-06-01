package com.unfurl.fabric.advisor;

public interface LlmProvider {
    LlmResponse complete(LlmRequest request);
}
