package com.unfurl.fabric.studio;

public record StudioCompileDraftCandidateRequest(
        String tenantId,
        String assemblyId,
        String sessionId,
        long expectedRevision,
        boolean sign,
        StudioDeploymentPolicyDraft deploymentPolicy
) {
}
