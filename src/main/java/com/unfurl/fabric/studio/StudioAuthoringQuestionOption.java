package com.unfurl.fabric.studio;

/**
 * Data Transfer Object: label/value option for a Studio authoring clarification
 * question. Pattern: value object. The label is display-only UI copy; the value
 * is the stable identifier or enum token Fabric and Foundry should consume in
 * structured follow-up answers.
 */
public record StudioAuthoringQuestionOption(
        String label,
        String value
) {
    /**
     * Invariant constructor: keeps select options renderable and machine-readable
     * even when a provider/tool omits one side of the pair.
     */
    public StudioAuthoringQuestionOption {
        label = label == null || label.isBlank() ? (value == null ? "" : value) : label;
        value = value == null || value.isBlank() ? label : value;
    }

    /**
     * Factory: creates an option whose label and value are intentionally the same.
     */
    public static StudioAuthoringQuestionOption of(String value) {
        return new StudioAuthoringQuestionOption(value, value);
    }
}
