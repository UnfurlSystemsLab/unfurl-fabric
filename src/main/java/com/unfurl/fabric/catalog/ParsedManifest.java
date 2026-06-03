package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.deployment.domain.ComponentShapeProfile;

/**
 * Result of parsing META-INF/unfurl-catalog.yaml. The two-block manifest separates a pure
 * DCP claim from non-DCP catalog metadata (lifecycle, artifact provenance, deployment binding).
 * The DCP schema stays pure; this wrapper is the catalog convention.
 */
public record ParsedManifest(
        Claim claim,
        Lifecycle lifecycle,
        AuthoredArtifact authoredArtifact,
        BindingDescriptor binding,
        ComponentShapeProfile componentShapeProfile
) {
    public ParsedManifest(
            Claim claim,
            Lifecycle lifecycle,
            AuthoredArtifact authoredArtifact,
            BindingDescriptor binding) {
        this(claim, lifecycle, authoredArtifact, binding, null);
    }

    public ParsedManifest {
        if (claim == null) {
            throw new IllegalArgumentException("claim block is required");
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("catalog.lifecycle block is required");
        }
        if (authoredArtifact == null) {
            throw new IllegalArgumentException("catalog.artifact block is required");
        }
        if (binding == null) {
            throw new IllegalArgumentException("catalog.binding block is required");
        }
    }
}
