package com.unfurl.fabric.studio;

import java.util.List;

public record StudioSaveDraftResponse(
        String status,
        StudioAssemblySummary assembly,
        List<String> warnings
) {
    public StudioSaveDraftResponse {
        status = status == null || status.isBlank() ? "SAVED" : status;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
