package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Claim;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public record StudioDcpProjectionRequest(
        String rootClaimUri,
        String focusClaimUri,
        Map<String, Claim> claimsByUri
) {
    public StudioDcpProjectionRequest {
        rootClaimUri = rootClaimUri == null ? "" : rootClaimUri.trim();
        focusClaimUri = focusClaimUri == null ? "" : focusClaimUri.trim();
        claimsByUri = claimsByUri == null ? Map.of() : Map.copyOf(claimsByUri);
    }

    public URI rootUri() {
        if (rootClaimUri.isBlank()) {
            throw new IllegalArgumentException("rootClaimUri is required");
        }
        return URI.create(rootClaimUri);
    }

    public URI focusUri() {
        return focusClaimUri.isBlank() ? rootUri() : URI.create(focusClaimUri);
    }

    public Map<URI, Claim> claimMap() {
        Map<URI, Claim> result = new LinkedHashMap<>();
        claimsByUri.forEach((key, claim) -> {
            if (claim == null) {
                return;
            }
            URI uri = key == null || key.isBlank() ? claim.identity().uri() : URI.create(key);
            result.put(uri, claim);
            result.putIfAbsent(claim.identity().uri(), claim);
        });
        if (result.isEmpty()) {
            throw new IllegalArgumentException("claimsByUri must contain at least the root claim");
        }
        return result;
    }
}
