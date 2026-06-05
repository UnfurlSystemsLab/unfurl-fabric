package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCatalogAdmissionResponse(
        String tenantId,
        String assemblyId,
        String status,
        List<StudioClaimVerificationResult> results,
        StudioCatalogVisualsResponse catalog
) {
    public StudioCatalogAdmissionResponse {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        status = status == null || status.isBlank() ? "PENDING" : status;
        results = results == null ? List.of() : List.copyOf(results);
    }
}
