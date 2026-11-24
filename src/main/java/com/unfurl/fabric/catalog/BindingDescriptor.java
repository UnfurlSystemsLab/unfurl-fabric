package com.unfurl.fabric.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unfurl.substrate.api.BindingMode;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a catalog component may be bound at runtime: its default binding mode, the full set of supported
 * modes, optional container/remote hints, and where it listens when it runs as its own service.
 *
 * <p>Pattern: immutable <b>value object</b> with invariants (default ∈ supported; service name and port given together)
 * and an {@code inProcessOnly()} factory.
 *
 * <p>{@code service_name} and {@code service_port} are catalog metadata, never part of the DCP claim, so they never change
 * a claim hash. Compile copies them into the signed binding plan for a component placed as a service of its own
 * (HLD-D §6.2b); the binding generator builds the DCP endpoint {@code http://<service_name>:<service_port>/dcp/
 * <capability>} from the plan, and the deploy emitter names and exposes the service from the same plan entry, so the two
 * always agree (HLD-api-documentation-artifacts §4.1). {@code readiness_path} is how the orchestrator probes that
 * service; it travels the same way, through {@link CatalogMetadata#serviceEndpoint()} (HLD-D §6.2b; HLD-E §4.0d).
 *
 * @param defaultMode        the preferred binding mode (required, must be in supportedModes).
 * @param supportedModes     all modes the component supports (defaults to {defaultMode}).
 * @param containerImage     optional container image for CONTAINER binding.
 * @param remoteEndpointHint optional endpoint hint for REMOTE binding.
 * @param serviceName        the service host name it listens on as its own service (a DNS label); null when it declares
 *                           none. Omitted from JSON when absent, so existing catalogs keep their bytes.
 * @param servicePort        the port it listens on, 1 to 65535; given exactly when {@code serviceName} is.
 * @param readinessPath      the readiness probe path of that service, when declared; only with {@code serviceName}, and
 *                           an absolute URL path ({@code ServiceEndpoint}'s rule). Omitted from JSON when absent.
 */
public record BindingDescriptor(
        BindingMode defaultMode,
        Set<BindingMode> supportedModes,
        String containerImage,
        String remoteEndpointHint,
        @JsonInclude(JsonInclude.Include.NON_NULL) String serviceName,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer servicePort,
        @JsonInclude(JsonInclude.Include.NON_NULL) String readinessPath
) {
    /** A DNS label (RFC 1123): what a container or Kubernetes service name may be. */
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    /** Compatibility constructor: a component that declares no service of its own. */
    public BindingDescriptor(BindingMode defaultMode, Set<BindingMode> supportedModes, String containerImage,
                             String remoteEndpointHint) {
        this(defaultMode, supportedModes, containerImage, remoteEndpointHint, null, null, null);
    }

    /** Compatibility constructor: a component that declares a service without a readiness path. */
    public BindingDescriptor(BindingMode defaultMode, Set<BindingMode> supportedModes, String containerImage,
                             String remoteEndpointHint, String serviceName, Integer servicePort) {
        this(defaultMode, supportedModes, containerImage, remoteEndpointHint, serviceName, servicePort, null);
    }

    /**
     * Compact constructor: requires a default mode, defaults supportedModes to {defaultMode}, and
     * enforces that the default mode is among the supported modes, that the service name and port are given together
     * and well formed, and that a readiness path is declared only for a service and is a valid probe path.
     */
    public BindingDescriptor {
        if (defaultMode == null) {
            throw new IllegalArgumentException("default binding mode is required");
        }
        supportedModes = supportedModes == null || supportedModes.isEmpty()
                ? Set.of(defaultMode)
                : Set.copyOf(supportedModes);
        if (!supportedModes.contains(defaultMode)) {
            throw new IllegalArgumentException(
                    "default binding mode " + defaultMode + " must be in supported modes " + supportedModes);
        }
        if ((serviceName == null) != (servicePort == null)) {
            throw new IllegalArgumentException("binding.service_name and binding.service_port are given together or not at all");
        }
        if (serviceName != null && !DNS_LABEL.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("binding.service_name must be a lower-case DNS label, not '" + serviceName + "'");
        }
        if (servicePort != null && (servicePort < 1 || servicePort > 65535)) {
            throw new IllegalArgumentException("binding.service_port must be 1 to 65535, not " + servicePort);
        }
        if (readinessPath != null && serviceName == null) {
            throw new IllegalArgumentException("binding.readiness_path is declared only with binding.service_name and "
                    + "binding.service_port: without a service there is nothing to probe");
        }
        if (readinessPath != null) {
            // One rule for the path: the plan's own (HLD-D §6.2b), so a catalog never admits what the plan would refuse.
            new com.unfurl.deployment.domain.ServiceEndpoint(serviceName, servicePort, Optional.of(readinessPath), Optional.empty());
        }
    }

    /**
     * @return a descriptor supporting only IN_PROCESS binding.
     */
    public static BindingDescriptor inProcessOnly() {
        return new BindingDescriptor(BindingMode.IN_PROCESS, Set.of(BindingMode.IN_PROCESS), null, null);
    }
}
