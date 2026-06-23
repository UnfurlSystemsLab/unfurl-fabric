package com.unfurl.fabric.studio;

import java.util.List;

public record StudioAuthoringQuestion(
        String id,
        String label,
        String type,
        List<String> options
) {
    public StudioAuthoringQuestion {
        id = id == null || id.isBlank() ? "question" : id;
        label = label == null ? "" : label;
        type = type == null || type.isBlank() ? "TEXT" : type;
        options = options == null ? List.of() : List.copyOf(options);
    }
}
