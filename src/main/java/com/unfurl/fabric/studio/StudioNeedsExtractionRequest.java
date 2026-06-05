package com.unfurl.fabric.studio;

import java.util.List;

public record StudioNeedsExtractionRequest(
        String targetApplicationName,
        List<String> fileNames,
        String defaultDeploymentTarget
) {
    public StudioNeedsExtractionRequest {
        targetApplicationName = targetApplicationName == null || targetApplicationName.isBlank()
                ? "target-application"
                : targetApplicationName.trim();
        fileNames = fileNames == null ? List.of() : List.copyOf(fileNames);
        defaultDeploymentTarget = defaultDeploymentTarget == null ? "" : defaultDeploymentTarget.trim();
    }
}
