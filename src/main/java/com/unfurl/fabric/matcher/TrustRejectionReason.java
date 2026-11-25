package com.unfurl.fabric.matcher;

/**
 * One reason the trust policy excluded a catalog entry the match considered: a row of a
 * {@link com.unfurl.fabric.trust.RejectedEntry}, flattened so Studio can show each reason on its own
 * (LLD-fabric-studio-api, <i>Structured match failures and trust rejections</i>).
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param coordinates the rejected entry's artifact coordinates.
 * @param reasonCode  the {@link com.unfurl.fabric.trust.RejectionReason} variant's name, for example
 *                    {@code VendorNotTrusted}.
 * @param message     the reason's own detail text.
 */
public record TrustRejectionReason(String coordinates, String reasonCode, String message) {
    /** Compact constructor: requires every field. */
    public TrustRejectionReason {
        if (coordinates == null || coordinates.isBlank()) {
            throw new IllegalArgumentException("coordinates are required");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
