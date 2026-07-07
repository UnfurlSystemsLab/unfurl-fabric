package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCatalogRemovalResponse(
        String tenantId,
        String catalogEntryId,
        String status,
        StudioCatalogVisualsResponse catalog,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Data Transfer Object invariant: normalizes the tenant-scoped catalog removal
     * response while preserving the updated catalog snapshot returned to Studio.
     */
    public StudioCatalogRemovalResponse {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
        status = status == null || status.isBlank() ? "REMOVED" : status;
        catalog = catalog == null
                ? new StudioCatalogVisualsResponse("sha256:empty-catalog", List.of())
                : catalog;
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
