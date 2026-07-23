package com.unfurl.fabric.studio;

import java.util.List;

/**
 * Data Transfer Object: one clarification question returned by the Studio
 * authoring bridge. Pattern: value object. The question id is the stable key
 * for `questionAnswers`; select options carry display labels separately from
 * machine-readable values.
 */
public record StudioAuthoringQuestion(
        String id,
        String label,
        String type,
        List<StudioAuthoringQuestionOption> options
) {
    /**
     * Data Transfer Object invariant: freezes option values while preserving the
     * explicit label/value split used by Studio and Foundry answer routing.
     */
    public StudioAuthoringQuestion {
        id = id == null || id.isBlank() ? "question" : id;
        label = label == null ? "" : label;
        type = type == null || type.isBlank() ? "TEXT" : type;
        options = options == null ? List.of() : List.copyOf(options);
    }
}
