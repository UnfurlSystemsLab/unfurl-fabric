package com.unfurl.fabric.studio;

import java.util.List;

public record StudioAuthoringConverseResponse(
        String kind,
        String assistantMessage,
        String sessionId,
        List<StudioAuthoringQuestion> questions,
        StudioAuthoringProposal proposal,
        List<String> unmet,
        List<String> warnings
) {
    public StudioAuthoringConverseResponse {
        kind = kind == null || kind.isBlank() ? "clarify" : kind;
        assistantMessage = assistantMessage == null ? "" : assistantMessage;
        sessionId = sessionId == null ? "" : sessionId;
        questions = questions == null ? List.of() : List.copyOf(questions);
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static StudioAuthoringConverseResponse clarify(
            String sessionId,
            String assistantMessage,
            List<StudioAuthoringQuestion> questions
    ) {
        return new StudioAuthoringConverseResponse(
                "clarify",
                assistantMessage,
                sessionId,
                questions,
                null,
                List.of(),
                List.of());
    }

    public static StudioAuthoringConverseResponse gap(
            String sessionId,
            String assistantMessage,
            List<String> unmet
    ) {
        return new StudioAuthoringConverseResponse(
                "gap",
                assistantMessage,
                sessionId,
                List.of(),
                null,
                unmet,
                List.of());
    }

    public static StudioAuthoringConverseResponse proposal(
            String sessionId,
            String assistantMessage,
            StudioAuthoringProposal proposal
    ) {
        return new StudioAuthoringConverseResponse(
                "proposal",
                assistantMessage,
                sessionId,
                List.of(),
                proposal,
                List.of(),
                List.of());
    }
}
