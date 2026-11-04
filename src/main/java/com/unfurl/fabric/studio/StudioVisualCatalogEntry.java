package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;

/**
 * Read model: one entry of a tenant's Studio catalog, as the palette shows it and as Studio state persists it.
 *
 * <p>{@code catalogManifest} is the exact {@code META-INF/unfurl-catalog.yaml} text read from the component's artifact
 * at admission or scan (DCP HLD-C §3.10). It is the only source compile uses for the component's claim: after a restart
 * or snapshot load Studio re-parses it and checks it against {@code claimHash}. It is empty for display-only entries
 * (built-in and package visual assets), which can be shown but not compiled.
 *
 * @param catalogEntryId      the entry id, unique within the tenant catalog.
 * @param claimHash           the pinned hash of the claim in {@code catalogManifest}.
 * @param artifactSha256      the SHA-256 computed from the artifact's bytes.
 * @param visualManifest      palette presentation metadata.
 * @param dynamicComposition  projection metadata (DCP type, raw dependencies, shape profile).
 * @param visualIntegrity     hashes of the visual assets.
 * @param warnings            admission warnings shown with the entry.
 * @param catalogManifest     the manifest text read from the artifact, or empty for a display-only entry.
 */
public record StudioVisualCatalogEntry(
        String catalogEntryId,
        String claimHash,
        String artifactSha256,
        Map<String, Object> visualManifest,
        Map<String, Object> dynamicComposition,
        Map<String, Object> visualIntegrity,
        List<String> warnings,
        String catalogManifest
) {
    /**
     * Display-only entry with projection metadata and no claim from an artifact.
     */
    public StudioVisualCatalogEntry(
            String catalogEntryId,
            String claimHash,
            String artifactSha256,
            Map<String, Object> visualManifest,
            Map<String, Object> dynamicComposition,
            Map<String, Object> visualIntegrity,
            List<String> warnings
    ) {
        this(catalogEntryId, claimHash, artifactSha256, visualManifest, dynamicComposition, visualIntegrity, warnings, "");
    }

    /**
     * Display-only entry without projection metadata or a claim from an artifact.
     */
    public StudioVisualCatalogEntry(
            String catalogEntryId,
            String claimHash,
            String artifactSha256,
            Map<String, Object> visualManifest,
            Map<String, Object> visualIntegrity,
            List<String> warnings
    ) {
        this(catalogEntryId, claimHash, artifactSha256, visualManifest, Map.of(), visualIntegrity, warnings, "");
    }

    /**
     * Invariant constructor: requires an id and copies collections; a missing manifest means display-only.
     */
    public StudioVisualCatalogEntry {
        if (catalogEntryId == null || catalogEntryId.isBlank()) {
            throw new IllegalArgumentException("catalogEntryId is required");
        }
        claimHash = claimHash == null || claimHash.isBlank() ? "sha256:unknown-claim" : claimHash;
        artifactSha256 = artifactSha256 == null || artifactSha256.isBlank() ? "sha256:unknown-artifact" : artifactSha256;
        visualManifest = visualManifest == null ? Map.of() : Map.copyOf(visualManifest);
        dynamicComposition = dynamicComposition == null ? Map.of() : Map.copyOf(dynamicComposition);
        visualIntegrity = visualIntegrity == null ? Map.of() : Map.copyOf(visualIntegrity);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        catalogManifest = catalogManifest == null ? "" : catalogManifest;
    }

    /**
     * @return true when the entry carries a claim read from its artifact and can therefore be compiled.
     */
    public boolean admitted() {
        return !catalogManifest.isBlank();
    }
}
