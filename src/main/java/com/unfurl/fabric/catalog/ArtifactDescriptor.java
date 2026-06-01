package com.unfurl.fabric.catalog;

public record ArtifactDescriptor(
        String coordinates,
        String packaging,
        String source,
        String sha256,
        String signature
) {
    public ArtifactDescriptor {
        if (coordinates == null || coordinates.isBlank()) {
            throw new IllegalArgumentException("artifact coordinates are required");
        }
        if (packaging == null || packaging.isBlank()) {
            throw new IllegalArgumentException("artifact packaging is required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("artifact source is required");
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("artifact sha256 is required");
        }
    }
}
