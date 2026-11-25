package com.unfurl.fabric.matcher;

import com.unfurl.fabric.trust.RejectedEntry;
import com.unfurl.fabric.trust.RejectionReason;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Presenter: renders a failed match as the operator-facing reasons it failed, both as text lines and as typed
 * {@link MatchFailureReason}s, so the CLI commands, Studio and the advisor all report the same message
 * (LLD-fabric-studio-api, <i>Structured match failures and trust rejections</i>).
 *
 * <p>Two failures are presented:
 * <ul>
 *   <li>a {@link MatchResult.NoMatch} from the {@code StructuralMatcher}: each unmet required capability first, with its
 *       reason and fix (see {@link UnmetCapabilityRequirement.Reason}), then each distinct conflict of the candidates
 *       that were considered but invalid, plus the trust rejections the match considered;</li>
 *   <li>a failed {@link CandidateValidity} of a Studio draft's own inventory: its conflicts, sorted by their text.</li>
 * </ul>
 * The text and the typed reasons are built from one list of causes, so they cannot disagree.
 */
public final class NoMatchExplanation {
    /** Prefix of a reason that comes from an unmet required capability. */
    public static final String UNMET_PREFIX = "unmet need: ";
    /** Prefix of a reason that comes from a candidate conflict. */
    public static final String CONFLICT_PREFIX = "conflict: ";
    /** The line printed when a NoMatch carries no cause at all. */
    static final String NO_CANDIDATE = "no candidate composition was generated for the needs";
    /** The details of a failed draft validity that carries no conflict. */
    static final String DRAFT_UNSATISFIED = "selected draft inventory does not satisfy the requested needs";

    /**
     * The reasons a match failed, in a stable order: unmet needs in needs order, then distinct conflicts in first-seen
     * order.
     *
     * @param noMatch the failed match result.
     * @return one prefixed, self-contained line per cause; never empty.
     */
    public static List<String> reasons(MatchResult.NoMatch noMatch) {
        List<String> lines = new ArrayList<>();
        for (MatchFailureReason cause : causes(noMatch)) {
            lines.add((cause.kind() == MatchFailureReason.Kind.UNMET_NEED ? UNMET_PREFIX : CONFLICT_PREFIX)
                    + cause.message());
        }
        if (lines.isEmpty()) {
            lines.add(NO_CANDIDATE);
        }
        return List.copyOf(lines);
    }

    /**
     * The typed causes of a failed match, in the order {@link #reasons} prints them: unmet needs in needs order, then
     * distinct conflicts (by text) in first-seen order.
     *
     * @param noMatch the failed match result.
     * @return the causes; empty only when the NoMatch carries none.
     */
    public static List<MatchFailureReason> causes(MatchResult.NoMatch noMatch) {
        List<MatchFailureReason> causes = new ArrayList<>();
        for (UnmetCapabilityRequirement missing : noMatch.missing()) {
            causes.add(new MatchFailureReason(MatchFailureReason.Kind.UNMET_NEED, missing.reason().name(),
                    missing.capability(), null, missing.detail()));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Conflict conflict : noMatch.conflicts()) {
            if (seen.add(conflict.detail())) {
                causes.add(conflictCause(conflict));
            }
        }
        return List.copyOf(causes);
    }

    /**
     * The trust rejections a failed match considered, one row per reason of each rejected entry, in the match's order.
     *
     * @param noMatch the failed match result.
     * @return the rejection rows; empty when the trust policy rejected nothing relevant.
     */
    public static List<TrustRejectionReason> trustRejections(MatchResult.NoMatch noMatch) {
        List<TrustRejectionReason> rows = new ArrayList<>();
        for (RejectedEntry rejected : noMatch.potentiallyRelevantRejections()) {
            for (RejectionReason reason : rejected.reasons()) {
                rows.add(new TrustRejectionReason(rejected.entry().artifact().coordinates(),
                        reason.getClass().getSimpleName(), reason.detail()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The details of a Studio draft whose own inventory fails validation: its conflicts' text, sorted and joined with
     * {@code "; "}.
     *
     * @param conflicts the validity's conflicts.
     * @return the details; a fixed sentence when there is no conflict.
     */
    public static String draftDetails(List<Conflict> conflicts) {
        List<MatchFailureReason> causes = draftCauses(conflicts);
        if (causes.isEmpty()) {
            return DRAFT_UNSATISFIED;
        }
        return String.join("; ", causes.stream().map(MatchFailureReason::message).toList());
    }

    /**
     * The typed causes of a Studio draft whose own inventory fails validation, in the order {@link #draftDetails}
     * joins them: sorted by their text.
     *
     * @param conflicts the validity's conflicts.
     * @return one cause per conflict.
     */
    public static List<MatchFailureReason> draftCauses(List<Conflict> conflicts) {
        return conflicts.stream()
                .sorted(Comparator.comparing(Conflict::detail))
                .map(NoMatchExplanation::conflictCause)
                .toList();
    }

    /** The typed cause of one conflict: its variant's code, the capability or component it names, and its text. */
    static MatchFailureReason conflictCause(Conflict conflict) {
        MatchFailureReason.Kind kind = MatchFailureReason.Kind.CONFLICT;
        return switch (conflict) {
            case Conflict.MissingCapabilityConflict c ->
                    new MatchFailureReason(kind, "MISSING_CAPABILITY", c.capability(), null, c.detail());
            case Conflict.VersionConflict c ->
                    new MatchFailureReason(kind, "VERSION", c.capability(), null, c.detail());
            case Conflict.OfferDetailConflict c ->
                    new MatchFailureReason(kind, "OFFER_DETAIL", c.capability(), null, c.detail());
            case Conflict.ArtifactConflict c ->
                    new MatchFailureReason(kind, "ARTIFACT", null, null, c.detail());
            case Conflict.RefusalConflict c ->
                    new MatchFailureReason(kind, "REFUSAL", null, null, c.detail());
            case Conflict.BindingConflict c ->
                    new MatchFailureReason(kind, "BINDING", c.capability(), null, c.detail());
            case Conflict.DependencyConflict c ->
                    new MatchFailureReason(kind, "DEPENDENCY", c.capability(), c.component(), c.detail());
        };
    }

    /** Utility class: prevent construction. */
    private NoMatchExplanation() {
    }
}
