package com.unfurl.fabric.studio;

import com.unfurl.fabric.verify.CatalogDriftReport;

import java.util.List;

/**
 * The response of {@code POST .../sessions/{sessionId}/verify}: a compile record's signed envelope checked with
 * Studio's trust keys, and its selections checked against the tenant's admitted catalog (LLD-fabric-studio-api,
 * <i>Contract diff and verification</i>).
 *
 * <p>Pattern: immutable <b>DTO</b> (read model) carrying the content of a
 * {@link com.unfurl.fabric.verify.FabricVerificationReport}. It never changes a session. An unsigned compile is
 * {@code NOT_SIGNED}, never reported as verified.
 *
 * @param available           whether the contract was verified.
 * @param absentReason        why not, when {@code available} is false.
 * @param provenance          the record that was verified, when one was found.
 * @param historical          whether that record is other than the session's current one.
 * @param signature           the signature check, when available.
 * @param catalogDrift        the catalog drift check, when available.
 * @param overallOk           whether the signature verified and the catalog is clean, when available.
 * @param diagnosticArtifacts the hash-pinned snapshot of this response.
 */
public record StudioVerificationResult(
        boolean available,
        StudioAbsentReason absentReason,
        StudioCompileProvenance provenance,
        boolean historical,
        Signature signature,
        CatalogDriftReport catalogDrift,
        Boolean overallOk,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * The signature check.
     *
     * @param ok     whether it verified.
     * @param result the {@code SignatureVerificationResult} variant: {@code Ok}, {@code KeyNotPinned},
     *               {@code SignatureInvalid} or {@code HashMismatch}.
     * @param detail the verifier's explanation; for {@code Ok}, the signing key's fingerprint and algorithm.
     */
    public record Signature(boolean ok, String result, String detail) {
    }

    /** Compact constructor: an unavailable result names its reason and carries no check. */
    public StudioVerificationResult {
        if (available == (absentReason != null)) {
            throw new IllegalArgumentException("an available result has no absent reason, and an unavailable one has one");
        }
        if (!available) {
            signature = null;
            catalogDrift = null;
            overallOk = null;
        }
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }

    /** The same response with its diagnostic snapshot attached. */
    StudioVerificationResult withDiagnostics(List<StudioExportArtifact> artifacts) {
        return new StudioVerificationResult(available, absentReason, provenance, historical, signature, catalogDrift,
                overallOk, artifacts);
    }
}
