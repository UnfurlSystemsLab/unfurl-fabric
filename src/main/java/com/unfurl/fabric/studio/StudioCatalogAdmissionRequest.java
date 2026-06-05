package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCatalogAdmissionRequest(
        String assemblyId,
        List<StudioComponentArtifactDraft> artifacts
) {
    public StudioCatalogAdmissionRequest {
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
