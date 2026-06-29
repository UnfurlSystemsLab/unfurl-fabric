package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.deployment.domain.ComponentShapeProfile;

/**
 * Result of parsing META-INF/unfurl-catalog.yaml. The two-block manifest separates a pure
 * DCP claim from non-DCP catalog metadata (lifecycle, artifact provenance, deployment binding).
 * The DCP schema stays pure; this wrapper is the catalog convention.
 *
 * <p>Pattern: immutable <b>value object</b> aggregating the parsed blocks.
 *
 * @param claim                 the pure DCP claim block (required).
 * @param lifecycle             the catalog lifecycle block (required).
 * @param authoredArtifact      the authored artifact block (required).
 * @param binding               the binding block (required).
 * @param componentShapeProfile optional deployment shape profile (may be null).
 */
public record ParsedManifest(
        Claim claim,
        Lifecycle lifecycle,
        AuthoredArtifact authoredArtifact,
        BindingDescriptor binding,
        ComponentShapeProfile componentShapeProfile
) {
    /**
     * Convenience constructor for manifests without a component shape profile.
     *
     * @param claim            the DCP claim block.
     * @param lifecycle        the lifecycle block.
     * @param authoredArtifact the authored artifact block.
     * @param binding          the binding block.
     */
    public ParsedManifest(
            Claim claim,
            Lifecycle lifecycle,
            AuthoredArtifact authoredArtifact,
            BindingDescriptor binding) {
        this(claim, lifecycle, authoredArtifact, binding, null);
    }

    /** Compact constructor: requires the four mandatory blocks (claim, lifecycle, artifact, binding). */
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
