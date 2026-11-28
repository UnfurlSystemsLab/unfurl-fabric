package com.unfurl.fabric.studio;

import com.unfurl.fabric.trust.TrustPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** The error code of a request with no complete input strategy (LLD-fabric-studio-api, Deployment Resolution). */
    public static final String INPUTS_INVALID = "DEPLOYMENT_RESOLVE_INPUTS_INVALID";

    /**
     * Invariant constructor: validates that one complete input strategy is present and
     * normalizes nullable text fields to empty strings for simpler routing.
     *
     * @throws IllegalArgumentException {@link #INPUTS_INVALID}, naming what is missing, when only one of
     *         {@code catalogPath} and {@code needsPath} is given, or when neither strategy is complete (for example a
     *         session request without its tenant or assembly).
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
            throw new IllegalArgumentException(INPUTS_INVALID + ": catalogPath and needsPath must be supplied together");
        }
        if (!hasFilesystemState && !hasSessionState) {
            throw new IllegalArgumentException(INPUTS_INVALID
                    + ": deployment resolve requires catalogPath + needsPath or tenantId + assemblyId + sessionId"
                    + missingSessionFields(tenantId, assemblyId, sessionId));
        }
    }

    /**
     * The session fields a request lacks, for the error message, so a session request that forgot its scope says which
     * of tenantId, assemblyId and sessionId are missing.
     *
     * @return {@code "; missing: tenantId, assemblyId"} and the like, or an empty string when none is missing.
     */
    private static String missingSessionFields(String tenantId, String assemblyId, String sessionId) {
        List<String> missing = new ArrayList<>();
        if (tenantId.isBlank()) missing.add("tenantId");
        if (assemblyId.isBlank()) missing.add("assemblyId");
        if (sessionId.isBlank()) missing.add("sessionId");
        return missing.isEmpty() ? "" : "; missing: " + String.join(", ", missing);
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
