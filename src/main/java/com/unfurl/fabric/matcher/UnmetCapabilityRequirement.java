package com.unfurl.fabric.matcher;

/**
 * A required capability that no allowed catalog entry could provide. Carried by
 * {@link MatchResult.NoMatch} so diagnostics can explain precisely which capability/version was unmet.
 *
 * <p>Pattern: immutable <b>value object</b> with a self-describing default detail message.
 *
 * @param requestedRange the requested capability version range (defaults to {@code *}).
 * @param capability     the unmet capability name (required).
 * @param detail         human-readable explanation (auto-derived when blank).
 */
public record UnmetCapabilityRequirement(
        String capability,
        String requestedRange,
        String detail
) {
    /** Compact constructor: requires a capability; defaults a blank range to {@code *} and derives a detail. */
    public UnmetCapabilityRequirement {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability is required");
        }
        if (requestedRange == null || requestedRange.isBlank()) {
            requestedRange = "*";
        }
        if (detail == null || detail.isBlank()) {
            detail = "no allowed catalog entry provides " + capability + " matching " + requestedRange;
        }
    }
}
