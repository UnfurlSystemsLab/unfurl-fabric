package com.unfurl.fabric.studio;

import com.unfurl.fabric.trust.TrustPolicy;

import java.nio.file.Path;

public record StudioDeploymentResolveRequest(
        Path catalogPath,
        Path needsPath,
        TrustPolicy trustPolicy,
        String candidateId,
        boolean autoSelectBest,
        StudioDeploymentPolicyDraft deploymentPolicy) {

    public StudioDeploymentResolveRequest {
        if (catalogPath == null) {
            throw new IllegalArgumentException("catalogPath is required");
        }
        if (needsPath == null) {
            throw new IllegalArgumentException("needsPath is required");
        }
    }
}
