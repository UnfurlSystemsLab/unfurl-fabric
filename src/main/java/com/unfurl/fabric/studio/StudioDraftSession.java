package com.unfurl.fabric.studio;

import java.time.Instant;
import java.util.List;

/**
 * Data Transfer Object: authoritative Studio draft-session state persisted and
 * emitted by Fabric.
 *
 * <p>Pattern: event-sourced read model. `intentLog` remains the source of draft
 * membership, while metadata such as `catalogFileId` and `displayName` provides
 * tenant history/provenance without changing compile semantics.
 */
public record StudioDraftSession(
        String tenantId,
        String assemblyId,
        String sessionId,
        String baseCatalogHash,
        String compositionMode,
        String needsId,
        String trustPolicyId,
        String currentCandidateId,
        long sceneRevision,
        List<StudioPlanningWarning> warnings,
        List<StudioCollaborator> collaborators,
        List<StudioIntentRecord> intentLog,
        String catalogFileId,
        String displayName,
        String sessionType,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastOpenedAt
) {
    /**
     * Convenience constructor: preserves concise service/test construction when
     * no session-history metadata has been supplied yet.
     */
    public StudioDraftSession(
            String tenantId,
            String assemblyId,
            String sessionId,
            String baseCatalogHash,
            String compositionMode,
            String needsId,
            String trustPolicyId,
            String currentCandidateId,
            long sceneRevision,
            List<StudioPlanningWarning> warnings,
            List<StudioCollaborator> collaborators,
            List<StudioIntentRecord> intentLog
    ) {
        this(
                tenantId,
                assemblyId,
                sessionId,
                baseCatalogHash,
                compositionMode,
                needsId,
                trustPolicyId,
                currentCandidateId,
                sceneRevision,
                warnings,
                collaborators,
                intentLog,
                "",
                "",
                "DRAFT",
                "OPEN",
                null,
                null,
                null);
    }

    /**
     * Data Transfer Object invariant: normalizes optional persisted metadata and
     * keeps intent/collaboration collections immutable.
     */
    public StudioDraftSession {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        sessionId = sessionId == null ? "" : sessionId;
        baseCatalogHash = baseCatalogHash == null ? "" : baseCatalogHash;
        compositionMode = compositionMode == null || compositionMode.isBlank() ? "DYNAMIC" : compositionMode;
        needsId = needsId == null ? "" : needsId;
        trustPolicyId = trustPolicyId == null ? "" : trustPolicyId;
        currentCandidateId = currentCandidateId == null ? "" : currentCandidateId;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        collaborators = collaborators == null ? List.of() : List.copyOf(collaborators);
        intentLog = intentLog == null ? List.of() : List.copyOf(intentLog);
        catalogFileId = catalogFileId == null ? "" : catalogFileId;
        displayName = displayName == null || displayName.isBlank() ? sessionId : displayName;
        sessionType = sessionType == null || sessionType.isBlank() ? "DRAFT" : sessionType;
        status = status == null || status.isBlank() ? "OPEN" : status;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        lastOpenedAt = lastOpenedAt == null ? updatedAt : lastOpenedAt;
    }
}
