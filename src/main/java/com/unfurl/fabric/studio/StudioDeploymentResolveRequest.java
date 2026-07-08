package com.unfurl.fabric.studio;

import com.unfurl.fabric.trust.TrustPolicy;

import java.nio.file.Path;

/**
 * Data Transfer Object: carries deployment-shape resolution inputs for either the
 * filesystem debug flow or the tenant-scoped Studio session flow.
 *
 * <p>Pattern: immutable request value object. Invariant: callers must supply either
 * {@code catalogPath + needsPath} or {@code tenantId + assemblyId + sessionId}; the UI
 * uses the session fields so it never needs access to server filesystem paths.
 */
public record StudioDeploymentResolveRequest(
        Path catalogPath,
        Path needsPath,
        TrustPolicy trustPolicy,
        String candidateId,
        boolean autoSelectBest,
        StudioDeploymentPolicyDraft deploymentPolicy,
        String tenantId,
        String assemblyId,
        String sessionId,
        String needsId,
        String needsYaml) {

    /**
     * Backward-compatible constructor for the original filesystem-only resolver tests and
     * CLI/debug callers.
     */
    public StudioDeploymentResolveRequest(
            Path catalogPath,
            Path needsPath,
            TrustPolicy trustPolicy,
            String candidateId,
            boolean autoSelectBest,
            StudioDeploymentPolicyDraft deploymentPolicy) {
        this(catalogPath, needsPath, trustPolicy, candidateId, autoSelectBest, deploymentPolicy,
                "", "", "", "", "");
    }

    /**
     * Invariant constructor: validates that one complete input strategy is present and
     * normalizes nullable text fields to empty strings for simpler routing.
     */
    public StudioDeploymentResolveRequest {
        tenantId = tenantId == null ? "" : tenantId.trim();
        assemblyId = assemblyId == null ? "" : assemblyId.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
        needsId = needsId == null ? "" : needsId.trim();
        needsYaml = needsYaml == null ? "" : needsYaml;
        boolean hasFilesystemState = catalogPath != null && needsPath != null;
        boolean hasSessionState = !tenantId.isBlank() && !assemblyId.isBlank() && !sessionId.isBlank();
        if ((catalogPath == null) != (needsPath == null)) {
            throw new IllegalArgumentException("catalogPath and needsPath must be supplied together");
        }
        if (!hasFilesystemState && !hasSessionState) {
            throw new IllegalArgumentException(
                    "deployment resolve requires catalogPath + needsPath or tenantId + assemblyId + sessionId");
        }
    }

    /**
     * Predicate: true when the resolver should scan a filesystem catalog and needs file.
     */
    public boolean usesFilesystemState() {
        return catalogPath != null && needsPath != null;
    }

    /**
     * Predicate: true when the resolver should consume the tenant draft session state.
     */
    public boolean usesSessionState() {
        return !tenantId.isBlank() && !assemblyId.isBlank() && !sessionId.isBlank();
    }
}
