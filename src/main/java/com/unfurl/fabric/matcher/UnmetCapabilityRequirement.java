package com.unfurl.fabric.matcher;

/**
 * A required capability that no allowed catalog entry could provide. Carried by
 * {@link MatchResult.NoMatch} so diagnostics can explain precisely which capability/version was unmet and why.
 *
 * <p>Every capability the needs require is mandatory: an unmet one fails planning and compilation, and the
 * {@link Reason} tells the operator what to change (HLD-unfurl-fabric, Core Flow step 3).
 *
 * <p>Pattern: immutable <b>value object</b> with a self-describing default detail message.
 *
 * @param capability     the unmet capability name (required).
 * @param requestedRange the requested capability version range (defaults to {@code *}).
 * @param reason         why no allowed entry satisfied it (required).
 * @param detail         human-readable explanation naming the fix (auto-derived when blank).
 */
public record UnmetCapabilityRequirement(
        String capability,
        String requestedRange,
        Reason reason,
        String detail
) {
    /**
     * Why a required capability is unmet, most specific first: the matcher reports the first reason that applies.
     */
    public enum Reason {
        /** An allowed entry offers the capability at a matching version, but not with the required offer details. */
        OFFER_DETAILS_UNSATISFIED,
        /** Allowed entries offer the capability, but only at versions outside the requested range. */
        VERSION_NOT_OFFERED,
        /** Only entries the trust policy rejected offer the capability. */
        REJECTED_BY_TRUST_POLICY,
        /** No catalog entry offers the capability at all. */
        NOT_IN_CATALOG
    }

    /** Compact constructor: requires a capability and reason; defaults a blank range to {@code *} and derives a detail. */
    public UnmetCapabilityRequirement {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability is required");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason is required");
        }
        if (requestedRange == null || requestedRange.isBlank()) {
            requestedRange = "*";
        }
        if (detail == null || detail.isBlank()) {
            detail = "the needs require " + capability + " " + requestedRange + ", but no allowed catalog entry provides it";
        }
    }
}
