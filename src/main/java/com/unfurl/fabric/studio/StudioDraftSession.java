package com.unfurl.fabric.studio;

import java.util.List;

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
        List<StudioIntentRecord> intentLog
) {
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
    }
}
