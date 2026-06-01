package com.unfurl.fabric.matcher;

public record UnmetCapabilityRequirement(
        String capability,
        String requestedRange,
        String detail
) {
    public UnmetCapabilityRequirement {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability is required");
        }
        if (requestedRange == null || requestedRange.isBlank()) {
            requestedRange = "*";
        }
        if (detail == null || detail.isBlank()) {
            detail = "no allowed catalog entry provides " + capability + " matching " + requestedRange;
        }
    }
}
