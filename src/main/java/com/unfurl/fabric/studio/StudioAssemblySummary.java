package com.unfurl.fabric.studio;

public record StudioAssemblySummary(
        String tenantId,
        String assemblyId,
        String targetApplicationName,
        String defaultDeploymentTarget,
        String needsId,
        String deploymentShape,
        String currentCandidateId,
        int sceneRevision
) {
    public StudioAssemblySummary {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (assemblyId == null || assemblyId.isBlank()) {
            throw new IllegalArgumentException("assemblyId is required");
        }
        targetApplicationName = targetApplicationName == null || targetApplicationName.isBlank()
                ? assemblyId
                : targetApplicationName;
        defaultDeploymentTarget = defaultDeploymentTarget == null ? "" : defaultDeploymentTarget;
        needsId = needsId == null ? "" : needsId;
        deploymentShape = deploymentShape == null || deploymentShape.isBlank() ? "CONTAINERIZED_SERVICE" : deploymentShape;
        currentCandidateId = currentCandidateId == null ? "" : currentCandidateId;
    }
}
