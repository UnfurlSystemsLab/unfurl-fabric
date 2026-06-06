package com.unfurl.fabric.studio;

public record StudioCreateDraftCompositionRequest(
        String tenantId,
        String assemblyId,
        String baseCatalogHash,
        String needsId,
        String trustPolicyId,
        String initialCandidateId,
        String collaboratorId,
        String collaboratorName
) {
}
