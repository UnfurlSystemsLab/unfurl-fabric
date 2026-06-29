package com.unfurl.fabric.studio;

import java.util.List;

public record StudioSaveDraftResponse(
        String status,
        StudioAssemblySummary assembly,
        List<String> warnings,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Backward-compatible constructor for save responses without downloadable draft
     * diagnostics.
     */
    public StudioSaveDraftResponse(String status, StudioAssemblySummary assembly, List<String> warnings) {
        this(status, assembly, warnings, List.of());
    }

    /**
     * Data Transfer Object invariant: freezes warnings and optional artifacts so saved
     * draft diagnostics are stable after response construction.
     */
    public StudioSaveDraftResponse {
        status = status == null || status.isBlank() ? "SAVED" : status;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
