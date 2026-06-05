package com.unfurl.fabric.studio;

public record StudioCreateAssemblyRequest(
        String assemblyId,
        String targetApplicationName,
        String defaultDeploymentTarget
) {
    public StudioCreateAssemblyRequest {
        if (assemblyId == null || assemblyId.isBlank()) {
            throw new IllegalArgumentException("assemblyId is required");
        }
        assemblyId = assemblyId.trim();
        targetApplicationName = targetApplicationName == null || targetApplicationName.isBlank()
                ? assemblyId
                : targetApplicationName.trim();
        defaultDeploymentTarget = defaultDeploymentTarget == null ? "" : defaultDeploymentTarget.trim();
    }
}
