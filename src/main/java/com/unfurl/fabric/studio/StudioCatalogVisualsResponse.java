package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCatalogVisualsResponse(
        String catalogHash,
        List<StudioVisualCatalogEntry> entries
) {
    public StudioCatalogVisualsResponse {
        catalogHash = catalogHash == null || catalogHash.isBlank() ? "sha256:empty-catalog" : catalogHash;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
