package com.unfurl.fabric.catalog;

import java.util.Optional;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Runtime-relevant catalog metadata pairing an entry's {@link Lifecycle} with its
 * {@link BindingDescriptor}. (The authored artifact block is enriched separately into an
 * {@code ArtifactDescriptor} by the scanner.)
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param lifecycle        the lifecycle block (required).
 * @param binding          the binding descriptor (required).
 * @param hostedActivation {@code hosted_activation}, when the component runs inside a host (§3 of
 *                         HLD-in-process-component-hosting); null and omitted from JSON when absent.
 * @param componentHost    {@code component_host}, when the component hosts others; null and omitted when absent.
 */
public record CatalogMetadata(
        Lifecycle lifecycle,
        BindingDescriptor binding,
        @JsonInclude(JsonInclude.Include.NON_NULL) HostedActivation hostedActivation,
        @JsonInclude(JsonInclude.Include.NON_NULL) ComponentHostDeclaration componentHost
) {
    /** Compatibility constructor: an entry that neither is hosted nor hosts others. */
    public CatalogMetadata(Lifecycle lifecycle, BindingDescriptor binding) {
        this(lifecycle, binding, null, null);
    }

    /** Compact constructor: requires both lifecycle and binding. */
    public CatalogMetadata {
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle is required");
        }
        if (binding == null) {
            throw new IllegalArgumentException("binding is required");
        }
    }

    /** Returns {@code hosted_activation}, when the component runs inside a host. */
    public Optional<HostedActivation> optionalHostedActivation() {
        return Optional.ofNullable(hostedActivation);
    }

    /** Returns {@code component_host}, when the component hosts others. */
    public Optional<ComponentHostDeclaration> optionalComponentHost() {
        return Optional.ofNullable(componentHost);
    }
}
