package com.unfurl.fabric.studio;

import java.util.Optional;

/**
 * The body of {@code POST .../sessions/{sessionId}/verify} (LLD-fabric-studio-api, <i>Contract diff and
 * verification</i>). Everything is optional: with nothing named, the session's current compile record is verified.
 *
 * <p>Pattern: immutable <b>DTO</b>.
 *
 * @param revision         a past revision to verify (its latest record), or null.
 * @param contractHash     a past contract hash to verify (its latest record), or null; ignored when
 *                         {@code revision} is given.
 * @param deploymentPolicy the policy the request would compile with now, so a current record compiled with another
 *                         policy is reported stale; null when the request names none.
 */
public record StudioVerifyRequest(
        Long revision,
        String contractHash,
        StudioDeploymentPolicyDraft deploymentPolicy
) {
    /** Compact constructor: a blank hash is no hash. */
    public StudioVerifyRequest {
        contractHash = contractHash == null || contractHash.isBlank() ? null : contractHash.trim();
    }

    /** The policy the request names, if any. */
    public Optional<StudioDeploymentPolicyDraft> requestPolicy() {
        return Optional.ofNullable(deploymentPolicy);
    }
}
