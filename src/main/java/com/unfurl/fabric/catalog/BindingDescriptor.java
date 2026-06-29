package com.unfurl.fabric.catalog;

import com.unfurl.substrate.api.BindingMode;

import java.util.Set;

/**
 * How a catalog component may be bound at runtime: its default binding mode, the full set of supported
 * modes, and optional container/remote hints.
 *
 * <p>Pattern: immutable <b>value object</b> with an invariant (default ∈ supported) and an
 * {@code inProcessOnly()} factory.
 *
 * @param defaultMode        the preferred binding mode (required, must be in supportedModes).
 * @param supportedModes     all modes the component supports (defaults to {defaultMode}).
 * @param containerImage     optional container image for CONTAINER binding.
 * @param remoteEndpointHint optional endpoint hint for REMOTE binding.
 */
public record BindingDescriptor(
        BindingMode defaultMode,
        Set<BindingMode> supportedModes,
        String containerImage,
        String remoteEndpointHint
) {
    /**
     * Compact constructor: requires a default mode, defaults supportedModes to {defaultMode}, and
     * enforces that the default mode is among the supported modes.
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
    }

    /**
     * @return a descriptor supporting only IN_PROCESS binding.
     */
    public static BindingDescriptor inProcessOnly() {
        return new BindingDescriptor(BindingMode.IN_PROCESS, Set.of(BindingMode.IN_PROCESS), null, null);
    }
}
