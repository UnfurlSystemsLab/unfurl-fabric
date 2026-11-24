package com.unfurl.fabric.catalog;

import java.util.Optional;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.unfurl.deployment.domain.CompositionMount;
import com.unfurl.deployment.domain.ServiceEndpoint;

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
 * @param compositionMount {@code composition_mount}, where the component's service reads its part of the signed
 *                         composition (HLD-E §4.0d); only with a declared service; null and omitted when absent.
 */
public record CatalogMetadata(
        Lifecycle lifecycle,
        BindingDescriptor binding,
        @JsonInclude(JsonInclude.Include.NON_NULL) HostedActivation hostedActivation,
        @JsonInclude(JsonInclude.Include.NON_NULL) ComponentHostDeclaration componentHost,
        @JsonInclude(JsonInclude.Include.NON_NULL) CompositionMount compositionMount
) {
    /** Compatibility constructor: an entry that neither is hosted nor hosts others. */
    public CatalogMetadata(Lifecycle lifecycle, BindingDescriptor binding) {
        this(lifecycle, binding, null, null, null);
    }

    /** Compatibility constructor: an entry without a composition mount. */
    public CatalogMetadata(Lifecycle lifecycle, BindingDescriptor binding, HostedActivation hostedActivation,
                           ComponentHostDeclaration componentHost) {
        this(lifecycle, binding, hostedActivation, componentHost, null);
    }

    /**
     * Compact constructor: requires both lifecycle and binding, and a declared service for a composition mount, since a
     * mount without a service has nothing to be mounted into (HLD-D §6.2b).
     */
    public CatalogMetadata {
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle is required");
        }
        if (binding == null) {
            throw new IllegalArgumentException("binding is required");
        }
        if (compositionMount != null && binding.serviceName() == null) {
            throw new IllegalArgumentException("composition_mount is declared only with binding.service_name and "
                    + "binding.service_port: without a service there is nothing to mount it into");
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

    /**
     * Projector: the service this component declares, as the deployment plan carries it (HLD-D §6.2b). It holds the name
     * and port, and the readiness path and composition mount when declared. Compile copies it into the plan, and the
     * binding generator and the deploy emitter read it from there only.
     *
     * @return empty when the component declares no service
     */
    public Optional<ServiceEndpoint> serviceEndpoint() {
        if (binding.serviceName() == null) {
            return Optional.empty();
        }
        return Optional.of(new ServiceEndpoint(binding.serviceName(), binding.servicePort(),
                Optional.ofNullable(binding.readinessPath()), Optional.ofNullable(compositionMount)));
    }
}
