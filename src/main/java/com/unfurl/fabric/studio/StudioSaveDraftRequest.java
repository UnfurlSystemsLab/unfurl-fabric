package com.unfurl.fabric.studio;

public record StudioSaveDraftRequest(
        String targetApplicationName,
        String needsId,
        String deploymentTarget,
        String deploymentShape,
        String currentCandidateId,
        int sceneRevision
) {
    public StudioSaveDraftRequest {
        targetApplicationName = targetApplicationName == null ? "" : targetApplicationName.trim();
        needsId = needsId == null ? "" : needsId.trim();
        deploymentTarget = deploymentTarget == null ? "" : deploymentTarget.trim();
        deploymentShape = deploymentShape == null || deploymentShape.isBlank()
                ? "CONTAINERIZED_SERVICE"
                : deploymentShape.trim();
        currentCandidateId = currentCandidateId == null ? "" : currentCandidateId.trim();
    }
}
