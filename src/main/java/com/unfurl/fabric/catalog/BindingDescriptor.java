package com.unfurl.fabric.catalog;

import com.unfurl.substrate.api.BindingMode;

import java.util.Set;

public record BindingDescriptor(
        BindingMode defaultMode,
        Set<BindingMode> supportedModes,
        String containerImage,
        String remoteEndpointHint
) {
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

    public static BindingDescriptor inProcessOnly() {
        return new BindingDescriptor(BindingMode.IN_PROCESS, Set.of(BindingMode.IN_PROCESS), null, null);
    }
}
