package com.unfurl.fabric.studio;

/**
 * Data Transfer Object: command body for creating a governed Studio draft
 * session.
 *
 * <p>Pattern: command DTO. Optional `catalogFileId` binds the draft to an
 * immutable tenant catalog file-version; optional `displayName` gives history a
 * human-readable title independent from physical artifact names.
 */
public record StudioCreateDraftCompositionRequest(
        String tenantId,
        String assemblyId,
        String baseCatalogHash,
        String needsId,
        String trustPolicyId,
        String initialCandidateId,
        String collaboratorId,
        String collaboratorName,
        String catalogFileId,
        String displayName
) {
    /**
     * Convenience constructor: keeps existing callers concise when they still
     * bind by catalog hash only.
     */
    public StudioCreateDraftCompositionRequest(
            String tenantId,
            String assemblyId,
            String baseCatalogHash,
            String needsId,
            String trustPolicyId,
            String initialCandidateId,
            String collaboratorId,
            String collaboratorName
    ) {
        this(
                tenantId,
                assemblyId,
                baseCatalogHash,
                needsId,
                trustPolicyId,
                initialCandidateId,
                collaboratorId,
                collaboratorName,
                "",
                "");
    }
}
