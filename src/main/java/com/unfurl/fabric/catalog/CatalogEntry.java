package com.unfurl.fabric.catalog;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.unfurl.deployment.domain.ComponentShapeProfile;
import com.unfurl.fabric.artifact.ArtifactDescriptor;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * One scanned catalog component: its (SHA-enriched) {@link ArtifactDescriptor}, its
 * {@link ClaimDescriptor}, its runtime {@link CatalogMetadata}, an optional deployment shape profile,
 * and the local JAR path (excluded from serialization).
 *
 * <p>Pattern: immutable <b>value object</b> exposing a canonical comparator for deterministic ordering.
 *
 * @param artifact             the SHA-pinned artifact descriptor (required).
 * @param claimDescriptor      the claim + claim hash (required).
 * @param metadata             lifecycle + binding metadata (required).
 * @param componentShapeProfile optional deployment shape profile (may be null).
 * @param localPath            local JAR path; {@code @JsonIgnore} so it never enters canonical bytes.
 */
public record CatalogEntry(
        ArtifactDescriptor artifact,
        ClaimDescriptor claimDescriptor,
        CatalogMetadata metadata,
        ComponentShapeProfile componentShapeProfile,
        @JsonIgnore Path localPath,
        @JsonIgnore List<CompanionArtifact> companions
) {
    /**
     * Constructor for entries without resolved companions: every entry that is not a hosted component, and hosted
     * components before scan-time resolution.
     */
    public CatalogEntry(
            ArtifactDescriptor artifact,
            ClaimDescriptor claimDescriptor,
            CatalogMetadata metadata,
            ComponentShapeProfile componentShapeProfile,
            Path localPath) {
        this(artifact, claimDescriptor, metadata, componentShapeProfile, localPath, List.of());
    }

    /**
     * Convenience constructor for entries without a component shape profile.
     *
     * @param artifact        the artifact descriptor.
     * @param claimDescriptor the claim descriptor.
     * @param metadata        the catalog metadata.
     * @param localPath       the local JAR path.
     */
    public CatalogEntry(
            ArtifactDescriptor artifact,
            ClaimDescriptor claimDescriptor,
            CatalogMetadata metadata,
            Path localPath) {
        this(artifact, claimDescriptor, metadata, null, localPath);
    }

    /** Compact constructor: requires artifact, claim descriptor, and metadata. */
    public CatalogEntry {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact descriptor is required");
        }
        if (claimDescriptor == null) {
            throw new IllegalArgumentException("claim descriptor is required");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("catalog metadata is required");
        }
        companions = companions == null ? List.of() : List.copyOf(companions);
    }

    /**
     * Returns this entry with the companions its {@code hosted_activation} names, as resolved by the scan
     * (HLD-in-process-component-hosting §3), in the order the activation lists them.
     */
    public CatalogEntry withCompanions(List<CompanionArtifact> resolved) {
        return new CatalogEntry(artifact, claimDescriptor, metadata, componentShapeProfile, localPath, resolved);
    }

    /**
     * Canonical ordering for catalog entries: by artifact coordinates, then SHA-256. Used everywhere a
     * deterministic, content-pinned order is required (catalog snapshot, candidate ids, profiles).
     */
    public static final Comparator<CatalogEntry> CANONICAL_ORDER =
            Comparator.comparing((CatalogEntry e) -> e.artifact().coordinates())
                    .thenComparing(e -> e.artifact().sha256());

    /**
     * @return the optional component shape profile (empty when absent).
     */
    public Optional<ComponentShapeProfile> optionalComponentShapeProfile() {
        return Optional.ofNullable(componentShapeProfile);
    }
}
