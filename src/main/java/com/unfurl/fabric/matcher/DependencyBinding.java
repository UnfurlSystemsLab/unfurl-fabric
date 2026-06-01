package com.unfurl.fabric.matcher;

public record DependencyBinding(
        String requirement,
        String providerCoordinates,
        boolean hostBound
) {
    public DependencyBinding {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("dependency requirement is required");
        }
    }
}
