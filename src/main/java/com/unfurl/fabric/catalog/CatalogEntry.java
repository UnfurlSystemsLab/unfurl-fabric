package com.unfurl.fabric.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;
import java.util.Comparator;

public record CatalogEntry(
        ArtifactDescriptor artifact,
        ClaimDescriptor claimDescriptor,
        CatalogMetadata metadata,
        @JsonIgnore Path localPath
) {
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
}
