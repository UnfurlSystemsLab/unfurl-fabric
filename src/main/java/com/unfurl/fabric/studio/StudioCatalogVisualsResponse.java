package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCatalogVisualsResponse(
        String catalogHash,
        List<StudioVisualCatalogEntry> entries,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Backward-compatible constructor for catalog callers that do not emit downloadable
     * diagnostic snapshots.
     */
    public StudioCatalogVisualsResponse(String catalogHash, List<StudioVisualCatalogEntry> entries) {
        this(catalogHash, entries, List.of());
    }

    /**
     * Data Transfer Object invariant: freezes catalog entries and optional diagnostic
     * artifact metadata for stable client rendering.
     */
    public StudioCatalogVisualsResponse {
        catalogHash = catalogHash == null || catalogHash.isBlank() ? "sha256:empty-catalog" : catalogHash;
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
