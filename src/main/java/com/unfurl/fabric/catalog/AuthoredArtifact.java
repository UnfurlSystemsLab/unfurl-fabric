package com.unfurl.fabric.catalog;

import com.unfurl.fabric.artifact.ArtifactDescriptor;

/**
 * Author-supplied artifact metadata as it appears inside {@code META-INF/unfurl-catalog.yaml}.
 * The scanner enriches this with a computed SHA-256 to produce the final {@link ArtifactDescriptor}
 * that the rest of the planner sees. Distinct type so the manifest schema and the runtime
 * descriptor cannot drift.
 */
public record AuthoredArtifact(
        String coordinates,
        String packaging,
        String source,
        String signature
) {
    public AuthoredArtifact {
        if (coordinates == null || coordinates.isBlank()) {
            throw new IllegalArgumentException("artifact.coordinates is required");
        }
        if (packaging == null || packaging.isBlank()) {
            packaging = "jar";
        }
        if (source == null || source.isBlank()) {
            source = "catalog";
        }
    }
}
