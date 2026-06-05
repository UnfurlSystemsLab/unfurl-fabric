package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;

public record StudioVisualCatalogEntry(
        String catalogEntryId,
        String claimHash,
        String artifactSha256,
        Map<String, Object> visualManifest,
        Map<String, Object> visualIntegrity,
        List<String> warnings
) {
    public StudioVisualCatalogEntry {
        if (catalogEntryId == null || catalogEntryId.isBlank()) {
            throw new IllegalArgumentException("catalogEntryId is required");
        }
        claimHash = claimHash == null || claimHash.isBlank() ? "sha256:unknown-claim" : claimHash;
        artifactSha256 = artifactSha256 == null || artifactSha256.isBlank() ? "sha256:unknown-artifact" : artifactSha256;
        visualManifest = visualManifest == null ? Map.of() : Map.copyOf(visualManifest);
        visualIntegrity = visualIntegrity == null ? Map.of() : Map.copyOf(visualIntegrity);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
