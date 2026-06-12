package com.unfurl.fabric.studio;

/**
 * One runtime capability exposed by the selected Unfurl substrate.
 *
 * <p>Dynamic Studio projections use these as first-class DCP offer
 * endpoints: component dependencies marked {@code ?substrate=true} or
 * {@code ?owner=substrate} bind to these ports and render as pipes down
 * to the substrate base plate.
 */
public record StudioSubstratePort(
        String portId,
        String capability,
        String label,
        String provider,
        String status
) {
    public StudioSubstratePort {
        capability = capability == null ? "" : capability;
        portId = portId == null || portId.isBlank()
                ? "substrate:" + capability.replace('.', '-')
                : portId;
        label = label == null || label.isBlank() ? capability : label;
        provider = provider == null || provider.isBlank() ? "unfurl-substrate" : provider;
        status = status == null || status.isBlank() ? "AVAILABLE" : status;
    }
}
