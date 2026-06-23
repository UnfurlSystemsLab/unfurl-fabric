package com.unfurl.fabric.studio.authoring;

import com.unfurl.foundry.substrate.ports.EmbeddingProvider;
import com.unfurl.foundry.substrate.ports.ModelProvider;
import com.unfurl.foundry.substrate.ports.ProviderKind;
import com.unfurl.foundry.substrate.ports.ProviderRegistry;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link ProviderRegistry} that resolves the single customer-configured {@link ModelProvider} for
 * the authoring agent's model ref. The customer binds the concrete provider in-perimeter; this host
 * adapter just exposes it to the neutral runtime. No embedder is provided.
 */
public final class SingleModelProviderRegistry implements ProviderRegistry {
    private final String modelRef;
    private final ModelProvider model;

    public SingleModelProviderRegistry(String modelRef, ModelProvider model) {
        this.modelRef = modelRef == null || modelRef.isBlank() ? "customer-configured" : modelRef;
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public boolean hasProvider(String name, ProviderKind kind, ExecutionContext context) {
        return kind == ProviderKind.LLM && matches(name);
    }

    @Override
    public Optional<ModelProvider> resolveModel(String name, ExecutionContext context) {
        return matches(name) ? Optional.of(model) : Optional.empty();
    }

    @Override
    public Optional<EmbeddingProvider> resolveEmbedder(String name, ExecutionContext context) {
        return Optional.empty();
    }

    private boolean matches(String name) {
        return name == null || name.isBlank() || modelRef.equals(name);
    }
}
