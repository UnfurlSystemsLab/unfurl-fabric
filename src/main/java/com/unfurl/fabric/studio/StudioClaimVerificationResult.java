package com.unfurl.fabric.studio;

import java.util.List;

public record StudioClaimVerificationResult(
        String fileName,
        String status,
        String catalogEntryId,
        String claimHash,
        List<String> warnings,
        List<StudioDcpDiagnostic> diagnostics
) {
    /**
     * Backward-compatible constructor for callers that only need summary warnings.
     */
    public StudioClaimVerificationResult(
            String fileName,
            String status,
            String catalogEntryId,
            String claimHash,
            List<String> warnings
    ) {
        this(fileName, status, catalogEntryId, claimHash, warnings, List.of());
    }

    /**
     * Data Transfer Object invariant: diagnostics and warnings are immutable lists so
     * downstream Studio responses cannot be mutated after admission completes.
     */
    public StudioClaimVerificationResult {
        fileName = fileName == null ? "" : fileName;
        status = status == null || status.isBlank() ? "REJECTED" : status;
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
        claimHash = claimHash == null ? "" : claimHash;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
