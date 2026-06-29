package com.unfurl.fabric.studio;

import java.util.List;

public record StudioCompileDraftCandidateResponse(
        String status,
        String candidateId,
        StudioExportArtifact contractArtifact,
        StudioExportArtifact substrateProfileArtifact,
        StudioExportArtifact signedContractArtifact,
        List<String> warnings,
        String reason,
        String details,
        long expectedRevision,
        long receivedRevision,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Backward-compatible constructor for compile responses without downloadable response
     * diagnostics.
     */
    public StudioCompileDraftCandidateResponse(
            String status,
            String candidateId,
            StudioExportArtifact contractArtifact,
            StudioExportArtifact substrateProfileArtifact,
            StudioExportArtifact signedContractArtifact,
            List<String> warnings,
            String reason,
            String details,
            long expectedRevision,
            long receivedRevision
    ) {
        this(status, candidateId, contractArtifact, substrateProfileArtifact, signedContractArtifact,
                warnings, reason, details, expectedRevision, receivedRevision, List.of());
    }

    /**
     * Data Transfer Object invariant: normalizes compile status metadata and freezes
     * warnings/artifacts for handoff diagnostics.
     */
    public StudioCompileDraftCandidateResponse {
        status = status == null ? "INVALID" : status;
        candidateId = candidateId == null ? "" : candidateId;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        reason = reason == null ? "" : reason;
        details = details == null ? "" : details;
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
