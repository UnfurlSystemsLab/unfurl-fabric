package com.unfurl.fabric.studio;

import java.util.List;

/**
 * Data Transfer Object: portable tenant catalog snapshot. The snapshot carries
 * Fabric's DCP-backed visual catalog read model so Studio can save it as JSON
 * and later ask Fabric to load it back into a tenant-scoped catalog.
 */
public record StudioCatalogSnapshot(
        String tenantId,
        String catalogHash,
        List<StudioVisualCatalogEntry> entries,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    public StudioCatalogSnapshot {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        catalogHash = catalogHash == null ? "" : catalogHash;
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
