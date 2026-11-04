package com.unfurl.fabric.matcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Presenter: renders a {@link MatchResult.NoMatch} as the operator-facing reasons it failed, one line per cause, so
 * the CLI commands, Studio and the advisor all report the same message.
 *
 * <p>Each unmet required capability is reported first, with its reason and fix (see
 * {@link UnmetCapabilityRequirement.Reason}); then each distinct conflict of the candidates that were considered but
 * invalid. A NoMatch always has at least one cause; if a caller constructs one without any, the single line says so
 * rather than printing nothing.
 */
public final class NoMatchExplanation {
    /** Prefix of a reason that comes from an unmet required capability. */
    public static final String UNMET_PREFIX = "unmet need: ";
    /** Prefix of a reason that comes from a candidate conflict. */
    public static final String CONFLICT_PREFIX = "conflict: ";

    /**
     * The reasons a match failed, in a stable order: unmet needs in needs order, then distinct conflicts in first-seen
     * order.
     *
     * @param noMatch the failed match result.
     * @return one prefixed, self-contained line per cause; never empty.
     */
    public static List<String> reasons(MatchResult.NoMatch noMatch) {
        List<String> lines = new ArrayList<>();
        noMatch.missing().forEach(missing -> lines.add(UNMET_PREFIX + missing.detail()));
        Set<String> conflicts = new LinkedHashSet<>();
        noMatch.conflicts().forEach(conflict -> conflicts.add(conflict.detail()));
        conflicts.forEach(detail -> lines.add(CONFLICT_PREFIX + detail));
        if (lines.isEmpty()) {
            lines.add("no candidate composition was generated for the needs");
        }
        return List.copyOf(lines);
    }

    /** Utility class: prevent construction. */
    private NoMatchExplanation() {
    }
}
