package com.unfurl.fabric.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.unfurl.deployment.domain.ComponentShapeProfile;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

public record CatalogEntry(
        ArtifactDescriptor artifact,
        ClaimDescriptor claimDescriptor,
        CatalogMetadata metadata,
        ComponentShapeProfile componentShapeProfile,
        @JsonIgnore Path localPath
) {
    public CatalogEntry(
            ArtifactDescriptor artifact,
            ClaimDescriptor claimDescriptor,
            CatalogMetadata metadata,
            Path localPath) {
        this(artifact, claimDescriptor, metadata, null, localPath);
    }

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
    }

    public static final Comparator<CatalogEntry> CANONICAL_ORDER =
            Comparator.comparing((CatalogEntry e) -> e.artifact().coordinates())
                    .thenComparing(e -> e.artifact().sha256());

    public Optional<ComponentShapeProfile> optionalComponentShapeProfile() {
        return Optional.ofNullable(componentShapeProfile);
    }
}
