package com.unfurl.fabric.studio;

import java.util.List;

public record StudioIntentResponse(
        String status,
        long newRevision,
        String updatedCandidateId,
        List<String> requiredSubstratePorts,
        List<String> warnings,
        String reason,
        String details,
        long expectedRevision,
        long receivedRevision,
        StudioDraftSession session
) {
    public StudioIntentResponse {
        status = status == null ? "INVALID" : status;
        updatedCandidateId = updatedCandidateId == null ? "" : updatedCandidateId;
        requiredSubstratePorts = requiredSubstratePorts == null ? List.of() : List.copyOf(requiredSubstratePorts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        reason = reason == null ? "" : reason;
        details = details == null ? "" : details;
    }

    public static StudioIntentResponse valid(long newRevision, String updatedCandidateId, StudioDraftSession session) {
        return new StudioIntentResponse(
                "VALID",
                newRevision,
                updatedCandidateId,
                List.of("secrets.provider"),
                List.of(),
                "",
                "",
                0,
                0,
                session);
    }

    public static StudioIntentResponse stale(long expectedRevision, long receivedRevision, StudioDraftSession session) {
        return new StudioIntentResponse(
                "STALE_REVISION",
                0,
                "",
                List.of(),
                List.of(),
                "",
                "",
                expectedRevision,
                receivedRevision,
                session);
    }

    public static StudioIntentResponse invalid(String reason, String details) {
        return new StudioIntentResponse(
                "INVALID",
                0,
                "",
                List.of(),
                List.of(),
                reason,
                details,
                0,
                0,
                null);
    }
}
