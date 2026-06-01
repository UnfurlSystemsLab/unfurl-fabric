package com.unfurl.fabric.catalog;

import com.unfurl.dcp.claim.Claim;

public record ClaimDescriptor(
        Claim claim,
        String claimHash
) {
    public ClaimDescriptor {
        if (claim == null) {
            throw new IllegalArgumentException("claim is required");
        }
        if (claimHash == null || claimHash.isBlank()) {
            throw new IllegalArgumentException("claim hash is required");
        }
    }
}
