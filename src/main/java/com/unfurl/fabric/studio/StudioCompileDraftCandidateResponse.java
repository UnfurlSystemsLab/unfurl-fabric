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
        long receivedRevision
) {
    public StudioCompileDraftCandidateResponse {
        status = status == null ? "INVALID" : status;
        candidateId = candidateId == null ? "" : candidateId;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        reason = reason == null ? "" : reason;
        details = details == null ? "" : details;
    }
}
