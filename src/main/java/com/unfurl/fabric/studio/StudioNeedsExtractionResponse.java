package com.unfurl.fabric.studio;

import java.util.List;

public record StudioNeedsExtractionResponse(
        String tenantId,
        String assemblyId,
        String needsId,
        String targetApplicationName,
        String suggestedNeedsYaml,
        String defaultDeploymentTarget,
        List<String> warnings,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Backward-compatible constructor for callers that pre-date downloadable needs
     * diagnostics.
     */
    public StudioNeedsExtractionResponse(
            String tenantId,
            String assemblyId,
            String needsId,
            String targetApplicationName,
            String suggestedNeedsYaml,
            String defaultDeploymentTarget,
            List<String> warnings
    ) {
        this(tenantId, assemblyId, needsId, targetApplicationName, suggestedNeedsYaml,
                defaultDeploymentTarget, warnings, List.of());
    }

    /**
     * Data Transfer Object invariant: normalizes extracted needs metadata and freezes
     * warnings/artifacts for repeatable diagnostic downloads.
     */
    public StudioNeedsExtractionResponse {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (assemblyId == null || assemblyId.isBlank()) {
            throw new IllegalArgumentException("assemblyId is required");
        }
        needsId = needsId == null || needsId.isBlank() ? assemblyId + "-extracted-needs" : needsId;
        targetApplicationName = targetApplicationName == null ? "" : targetApplicationName;
        suggestedNeedsYaml = suggestedNeedsYaml == null ? "" : suggestedNeedsYaml;
        defaultDeploymentTarget = defaultDeploymentTarget == null ? "" : defaultDeploymentTarget;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
