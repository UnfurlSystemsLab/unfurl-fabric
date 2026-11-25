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

    /** The planning warning a session without a trust policy carries (LLD-fabric-studio-api, Permissive trust policy warning). */
    public static final String TRUST_POLICY_PERMISSIVE = "TRUST_POLICY_PERMISSIVE";

    /**
     * Data Transfer Object invariant: normalizes optional persisted metadata and
     * keeps intent/collaboration collections immutable.
     *
     * <p>Derived warning: {@value #TRUST_POLICY_PERMISSIVE} is present exactly when {@code trustPolicyId} is blank, since
     * Fabric then plans under {@code TrustPolicy.permissive()} ({@code StudioDeploymentService}). It is derived here, the one
     * constructor every session passes through, so a stored, replayed or copied session can never carry a stale value, and
     * Studio displays it without inferring it.
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
        warnings = withTrustPolicyWarning(warnings == null ? List.of() : warnings, trustPolicyId);
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

    /** The warnings with {@value #TRUST_POLICY_PERMISSIVE} present exactly when there is no trust policy, once. */
    private static List<StudioPlanningWarning> withTrustPolicyWarning(List<StudioPlanningWarning> warnings, String trustPolicyId) {
        java.util.ArrayList<StudioPlanningWarning> result = new java.util.ArrayList<>();
        for (StudioPlanningWarning warning : warnings) {
            if (warning != null && !TRUST_POLICY_PERMISSIVE.equals(warning.code())) result.add(warning);
        }
        if (trustPolicyId.isBlank()) {
            result.add(new StudioPlanningWarning(TRUST_POLICY_PERMISSIVE,
                    "No trust policy is set for this session; every catalog entry is trusted."));
        }
        return List.copyOf(result);
    }
}
