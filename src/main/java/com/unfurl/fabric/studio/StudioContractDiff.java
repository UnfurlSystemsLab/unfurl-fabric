package com.unfurl.fabric.studio;

import com.unfurl.fabric.verify.ContractDiff;

import java.util.List;

/**
 * The response of {@code GET .../sessions/{sessionId}/contract-diff}: two compile records of a session compared by
 * {@link com.unfurl.fabric.verify.ContractDiffer} (LLD-fabric-studio-api, <i>Contract diff and verification</i>).
 *
 * <p>Pattern: immutable <b>DTO</b> (read model). It never changes a session. When {@code available} is false,
 * {@code absentReason} says why and {@code diff} is absent: a stale or unavailable comparison is never returned beside
 * current values.
 *
 * @param available           whether a diff is returned.
 * @param absentReason        why not, when {@code available} is false.
 * @param base                the record at {@code baseRevision}, when one was found.
 * @param target              the record compared against it (the current record, or the one at {@code revision}),
 *                            when one was found.
 * @param historical          whether {@code target} is a record other than the session's current one.
 * @param diff                the comparison, when available.
 * @param diagnosticArtifacts the hash-pinned snapshot of this response.
 */
public record StudioContractDiff(
        boolean available,
        StudioAbsentReason absentReason,
        StudioCompileProvenance base,
        StudioCompileProvenance target,
        boolean historical,
        ContractDiff diff,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /** Compact constructor: an unavailable diff names its reason and carries no comparison. */
    public StudioContractDiff {
        if (available == (absentReason != null)) {
            throw new IllegalArgumentException("an available diff has no absent reason, and an unavailable one has one");
        }
        if (!available) {
            diff = null;
        }
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }

    /** The same response with its diagnostic snapshot attached. */
    StudioContractDiff withDiagnostics(List<StudioExportArtifact> artifacts) {
        return new StudioContractDiff(available, absentReason, base, target, historical, diff, artifacts);
    }
}
