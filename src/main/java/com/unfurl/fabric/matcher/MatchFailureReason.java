package com.unfurl.fabric.matcher;

/**
 * One typed cause of a failed match: an unmet need or a candidate conflict. {@link NoMatchExplanation} builds these from
 * the same causes as its text lines, so a typed reason and its line always say the same thing
 * (LLD-fabric-studio-api, <i>Structured match failures and trust rejections</i>).
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param kind                 {@link Kind#UNMET_NEED} or {@link Kind#CONFLICT}.
 * @param reasonCode           for an unmet need the {@link UnmetCapabilityRequirement.Reason} name; for a conflict the
 *                             {@link Conflict} variant's code ({@code MISSING_CAPABILITY}, {@code VERSION},
 *                             {@code OFFER_DETAIL}, {@code ARTIFACT}, {@code REFUSAL}, {@code BINDING},
 *                             {@code DEPENDENCY}).
 * @param capability           the capability the cause names, or null when it names none.
 * @param componentCoordinates for a {@code DEPENDENCY} conflict, the component whose claim declares the dependency;
 *                             otherwise null.
 * @param message              the cause's own detail text, which names its fix.
 */
public record MatchFailureReason(
        Kind kind,
        String reasonCode,
        String capability,
        String componentCoordinates,
        String message
) {
    /** The two kinds of cause. */
    public enum Kind {
        /** A capability the needs require that no allowed catalog entry provides. */
        UNMET_NEED,
        /** A conflict of a candidate that was considered but invalid. */
        CONFLICT
    }

    /** Compact constructor: requires a kind, a reason code and a message. */
    public MatchFailureReason {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
