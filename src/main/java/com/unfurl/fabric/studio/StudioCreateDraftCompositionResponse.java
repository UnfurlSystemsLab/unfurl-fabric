package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCreateDraftCompositionResponse(
        StudioDraftSession session,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Backward-compatible constructor for session creation responses without downloadable
     * diagnostic snapshots.
     */
    public StudioCreateDraftCompositionResponse(StudioDraftSession session) {
        this(session, List.of());
    }

    /**
     * Data Transfer Object invariant: freezes optional diagnostic artifact metadata while
     * preserving the authoritative draft session.
     */
    public StudioCreateDraftCompositionResponse {
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
