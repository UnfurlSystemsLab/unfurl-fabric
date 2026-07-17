package com.unfurl.fabric.studio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object: carries the result of a Studio authoring turn. Foundry-backed
 * turns may return clarification, gaps, or catalog-backed proposals. Flow-owned
 * runbook execution uses the Studio tool gateway and Flow workflow artifacts instead
 * of this authoring response.
 */
public record StudioAuthoringConverseResponse(
        String kind,
        String assistantMessage,
        String sessionId,
        List<StudioAuthoringQuestion> questions,
        StudioAuthoringProposal proposal,
        List<String> unmet,
        List<String> warnings,
        String phase,
        Integer step,
        List<Map<String, Object>> toolCalls,
        List<Map<String, Object>> artifacts,
        Map<String, Object> gap
) {
    /**
     * Invariant constructor: freezes collection/map payloads while preserving the
     * optional diagnostic fields used by existing response consumers.
     */
    public StudioAuthoringConverseResponse {
        kind = kind == null || kind.isBlank() ? "clarify" : kind;
        assistantMessage = assistantMessage == null ? "" : assistantMessage;
        sessionId = sessionId == null ? "" : sessionId;
        questions = questions == null ? List.of() : List.copyOf(questions);
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        phase = phase == null ? "" : phase;
        toolCalls = immutableListOfMaps(toolCalls);
        artifacts = immutableListOfMaps(artifacts);
        gap = immutableMap(gap);
    }

    /**
     * Factory: creates a clarification response without execution metadata.
     */
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
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                Map.of());
    }

    /**
     * Factory: creates a blocking gap response without proposal or execution payload.
     */
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
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                Map.of());
    }

    /**
     * Factory: creates a catalog-backed proposal response.
     */
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
                List.of(),
                "",
                null,
                List.of(),
                List.of(),
                Map.of());
    }

    /**
     * Collection freezer: recursively freezes the top-level execution list maps while
     * preserving nested JSON-compatible payloads.
     */
    private static List<Map<String, Object>> immutableListOfMaps(List<Map<String, Object>> value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return value.stream().map(StudioAuthoringConverseResponse::immutableMap).toList();
    }

    /**
     * Map freezer: protects DTO-level maps from mutation after response creation.
     */
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
