package com.unfurl.fabric.compiler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DecisionAudit(
        Instant compiledAt,
        List<String> alternativesConsidered,
        List<String> selectionReasons,
        Map<String, Integer> scoreBreakdown,
        List<String> planningWarnings,
        String selectionMode
) {
    public DecisionAudit {
        if (compiledAt == null) {
            throw new IllegalArgumentException("compiledAt is required");
        }
        alternativesConsidered = sortedCopy(alternativesConsidered);
        selectionReasons = sortedCopy(selectionReasons);
        scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(scoreBreakdown));
        planningWarnings = sortedCopy(planningWarnings);
    }

    public DecisionAudit(
            Instant compiledAt,
            List<String> alternativesConsidered,
            List<String> selectionReasons,
            Map<String, Integer> scoreBreakdown,
            List<String> planningWarnings) {
        this(compiledAt, alternativesConsidered, selectionReasons, scoreBreakdown, planningWarnings, null);
    }

    public DecisionAudit withSelectionMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("selection mode is required");
        }
        return new DecisionAudit(compiledAt, alternativesConsidered, selectionReasons,
                scoreBreakdown, planningWarnings, mode);
    }

    private static List<String> sortedCopy(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }
}
