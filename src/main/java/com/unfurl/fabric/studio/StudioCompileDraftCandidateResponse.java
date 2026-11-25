package com.unfurl.fabric.studio;

import com.unfurl.fabric.matcher.MatchFailureReason;
import com.unfurl.fabric.matcher.TrustRejectionReason;

import java.util.List;

/**
 * Data Transfer Object: returns Studio compile status plus hash-pinned handoff,
 * support, and diagnostic artifact metadata.
 *
 * <p>Pattern: immutable DTO. The primary contract artifacts are light DCP handoff
 * files; support artifacts are required companions for packaging/runtime hydration;
 * diagnostic artifacts are replay/debug snapshots.
 *
 * <p>A {@code COMPILED} response carries the {@link StudioCompileRecord} it appended to the session as
 * {@code compileRecord}, so Studio pins the result to Fabric's own hashes and never computes one. A {@code NO_MATCH}
 * carries its typed causes in {@code reasons} and {@code trustRejections} beside the unchanged {@code details}
 * (LLD-fabric-studio-api, <i>Compile records and freshness</i>, <i>Structured match failures and trust rejections</i>).
 */
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
        List<StudioExportArtifact> supportArtifacts,
        List<StudioExportArtifact> diagnosticArtifacts,
        List<MatchFailureReason> reasons,
        List<TrustRejectionReason> trustRejections,
        StudioCompileRecord compileRecord
) {
    /**
     * Compatibility constructor: a response without typed causes or a compile record (anything but a successful
     * compile or a {@code NO_MATCH}).
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
            long receivedRevision,
            List<StudioExportArtifact> supportArtifacts,
            List<StudioExportArtifact> diagnosticArtifacts
    ) {
        this(status, candidateId, contractArtifact, substrateProfileArtifact, signedContractArtifact,
                warnings, reason, details, expectedRevision, receivedRevision, supportArtifacts, diagnosticArtifacts,
                List.of(), List.of(), null);
    }

    /**
     * Backward-compatible constructor for compile responses without downloadable response
     * support or diagnostic artifacts.
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
                warnings, reason, details, expectedRevision, receivedRevision, List.of(), List.of());
    }

    /**
     * Backward-compatible constructor for compile responses that only distinguish
     * diagnostics. New callers should pass support artifacts explicitly.
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
            long receivedRevision,
            List<StudioExportArtifact> diagnosticArtifacts
    ) {
        this(status, candidateId, contractArtifact, substrateProfileArtifact, signedContractArtifact,
                warnings, reason, details, expectedRevision, receivedRevision, List.of(), diagnosticArtifacts);
    }

    /**
     * Data Transfer Object invariant: normalizes compile status metadata and freezes
     * warnings/artifacts for handoff support and diagnostics.
     */
    public StudioCompileDraftCandidateResponse {
        status = status == null ? "INVALID" : status;
        candidateId = candidateId == null ? "" : candidateId;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        reason = reason == null ? "" : reason;
        details = details == null ? "" : details;
        supportArtifacts = supportArtifacts == null ? List.of() : List.copyOf(supportArtifacts);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        trustRejections = trustRejections == null ? List.of() : List.copyOf(trustRejections);
    }
}
