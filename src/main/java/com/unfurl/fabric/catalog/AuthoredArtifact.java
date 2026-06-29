package com.unfurl.fabric.catalog;

import com.unfurl.fabric.artifact.ArtifactDescriptor;

/**
 * Author-supplied artifact metadata as it appears inside {@code META-INF/unfurl-catalog.yaml}.
 * The scanner enriches this with a computed SHA-256 to produce the final {@link ArtifactDescriptor}
 * that the rest of the planner sees. Distinct type so the manifest schema and the runtime
 * descriptor cannot drift.
 *
 * <p>Pattern: immutable <b>DTO/value object</b> with defaulting compact constructor.
 *
 * @param coordinates Maven-style artifact coordinates (required).
 * @param packaging   packaging type (defaults to {@code jar}).
 * @param source      provenance source (defaults to {@code catalog}).
 * @param signature   optional detached signature reference.
 */
public record AuthoredArtifact(
        String coordinates,
        String packaging,
        String source,
        String signature
) {
    /** Compact constructor: requires coordinates; defaults packaging/source when blank. */
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
