package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCatalogAdmissionResponse(
        String tenantId,
        String assemblyId,
        String status,
        List<StudioClaimVerificationResult> results,
        StudioCatalogVisualsResponse catalog,
        StudioExportArtifact claimBundleArtifact
) {
    /**
     * Backward-compatible constructor for older callers that only return admission results
     * and the updated catalog without a downloadable resolved-claims bundle.
     */
    public StudioCatalogAdmissionResponse(
            String tenantId,
            String assemblyId,
            String status,
            List<StudioClaimVerificationResult> results,
            StudioCatalogVisualsResponse catalog
    ) {
        this(tenantId, assemblyId, status, results, catalog, null);
    }

    /**
     * Data Transfer Object invariant: normalizes admission metadata and freezes per-file
     * results while leaving the optional claim bundle absent when no artifact verified.
     */
    public StudioCatalogAdmissionResponse {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        status = status == null || status.isBlank() ? "PENDING" : status;
        results = results == null ? List.of() : List.copyOf(results);
    }
}
