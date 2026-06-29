package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;

/**
 * Pairs a parsed DCP {@link Claim} with its canonical content hash, so the planner can pin the exact
 * claim bytes it observed into compiled contracts.
 *
 * <p>Pattern: immutable <b>value object</b>.
 *
 * @param claim     the parsed DCP claim (required).
 * @param claimHash the canonical claim hash (required, non-blank).
 */
public record ClaimDescriptor(
        Claim claim,
        String claimHash
) {
    /** Compact constructor: requires a non-null claim and a non-blank hash. */
    public ClaimDescriptor {
        if (claim == null) {
            throw new IllegalArgumentException("claim is required");
        }
        if (claimHash == null || claimHash.isBlank()) {
            throw new IllegalArgumentException("claim hash is required");
        }
    }
}
