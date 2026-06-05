package com.unfurl.fabric.studio;

import java.util.List;

public record StudioClaimVerificationResult(
        String fileName,
        String status,
        String catalogEntryId,
        String claimHash,
        List<String> warnings
) {
    public StudioClaimVerificationResult {
        fileName = fileName == null ? "" : fileName;
        status = status == null || status.isBlank() ? "REJECTED" : status;
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
        claimHash = claimHash == null ? "" : claimHash;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
