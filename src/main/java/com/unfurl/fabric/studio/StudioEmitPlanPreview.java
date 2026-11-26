package com.unfurl.fabric.studio;

import java.util.List;

/**
 * The response of {@code GET .../sessions/{sessionId}/emit-plan?environment} (LLD-fabric-studio-api,
 * <i>StudioEmitPlanPreview</i>): what a deploy of the session's current compile to one deployment environment emits,
 * produced by the deploy emitter's real pipeline in an isolated, deleted folder.
 *
 * <p>Pattern: immutable <b>DTO</b> (read model). Invariant: {@code available} exactly when there is no
 * {@code absentReason}; an unavailable preview has empty lists and no emit facts, and keeps the record and environment it
 * was asked about. Secret values never appear: secret requests and bindings name references only. The hashes describe this
 * preview run, so Studio labels them preview hashes and never compares them with a deployed manifest.
 *
 * @param tenantId            the tenant.
 * @param assemblyId          the assembly.
 * @param sessionId           the draft session.
 * @param revision            the session revision when the preview was answered.
 * @param environment         the deployment environment's name, as asked.
 * @param environmentRevision the environment's revision, or null when it is unknown.
 * @param environmentSha256   the {@code contentSha256} of the environment the preview read, or null when it is unknown;
 *                            Studio pins the answer to it, since a re-created environment reuses revisions.
 * @param provenance          the compile record the preview read, current or not; null before any compile.
 * @param available           whether the emit ran and produced a plan.
 * @param absentReason        why not, when unavailable, under the refusing step's own code.
 * @param targetKind          the emitter that rendered it.
 * @param artifacts           the emitted artifacts, with this run's hashes.
 * @param requiredSecrets     the secret requests: name, bound component, store hint; never a value.
 * @param runtimeBindings     the generated runtime bindings, by reference names only.
 * @param bindingsSha256      this run's runtime-binding set hash.
 * @param emitManifestSha256  this run's emit-manifest hash.
 * @param diagnosticArtifacts the hash-pinned snapshot of this response.
 */
public record StudioEmitPlanPreview(
        String tenantId,
        String assemblyId,
        String sessionId,
        long revision,
        String environment,
        Long environmentRevision,
        String environmentSha256,
        StudioCompileProvenance provenance,
        boolean available,
        StudioAbsentReason absentReason,
        String targetKind,
        List<Artifact> artifacts,
        List<SecretRequest> requiredSecrets,
        List<Binding> runtimeBindings,
        String bindingsSha256,
        String emitManifestSha256,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /** Compact constructor: frozen lists, and the availability invariant. */
    public StudioEmitPlanPreview {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        requiredSecrets = requiredSecrets == null ? List.of() : List.copyOf(requiredSecrets);
        runtimeBindings = runtimeBindings == null ? List.of() : List.copyOf(runtimeBindings);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
        if (available == (absentReason != null)) {
            throw new IllegalArgumentException("an emit plan is available exactly when it has no absent reason");
        }
        if (!available && (!artifacts.isEmpty() || !requiredSecrets.isEmpty() || !runtimeBindings.isEmpty())) {
            throw new IllegalArgumentException("an unavailable emit plan carries no emit facts");
        }
    }

    /** The same response with its diagnostic snapshot attached. */
    StudioEmitPlanPreview withDiagnosticArtifacts(List<StudioExportArtifact> snapshot) {
        return new StudioEmitPlanPreview(tenantId, assemblyId, sessionId, revision, environment, environmentRevision,
                environmentSha256, provenance, available, absentReason, targetKind, artifacts, requiredSecrets, runtimeBindings,
                bindingsSha256, emitManifestSha256, snapshot);
    }

    /**
     * One emitted artifact.
     *
     * @param path   the artifact's path in the emitted plan.
     * @param kind   the emitter's artifact kind, for example {@code COMPOSE} or {@code RUNTIME_PROFILE}.
     * @param sha256 this run's hash of it.
     */
    public record Artifact(String path, String kind, String sha256) {
    }

    /**
     * One secret the deployment needs (HLD-E §5.3), by name only.
     *
     * @param name             the secret's name, for example {@code UNFURL_FOUNDRY_CREDENTIAL_KEY}.
     * @param boundComponentId the component it is bound to.
     * @param targetStoreHint  where the target expects it, for example {@code local-compose-env}.
     */
    public record SecretRequest(String name, String boundComponentId, String targetStoreHint) {
    }

    /**
     * One generated runtime binding (DCP HLD-C2 §C), by reference names only.
     *
     * @param bindingId          the binding's id.
     * @param contractId         the contract it binds.
     * @param capability         the provider capability of that contract; null for the root binding.
     * @param providerComponent  the provider component's claim URI.
     * @param providerNodeId     the draft node of the provider; null for the root binding or a provider outside the draft.
     * @param deploymentKind     how the provider is deployed, for example {@code CONTAINER}.
     * @param baseUrl            the provider's base URL, when the binding names one.
     * @param enabled            whether the binding is enabled ({@code runtime_policy.enabled}).
     * @param timeoutMs          the runtime timeout ({@code runtime_policy.timeout_ms}).
     * @param auditEnabled       whether audit is enabled ({@code runtime_policy.audit_enabled}).
     * @param telemetryNamespace the telemetry namespace.
     * @param secretRefNames     the configuration keys whose values are secret references, and {@code credentials} when the
     *                           provider has a credentials reference; never the references' targets.
     * @param configRefNames     the configuration keys whose values are config references, and {@code baseUrl} when the
     *                           base URL is a reference.
     */
    public record Binding(String bindingId, String contractId, String capability, String providerComponent,
                          String providerNodeId, String deploymentKind, String baseUrl, boolean enabled, Integer timeoutMs,
                          boolean auditEnabled, String telemetryNamespace, List<String> secretRefNames,
                          List<String> configRefNames) {
        /** Compact constructor: frozen lists. */
        public Binding {
            secretRefNames = secretRefNames == null ? List.of() : List.copyOf(secretRefNames);
            configRefNames = configRefNames == null ? List.of() : List.copyOf(configRefNames);
        }
    }
}
