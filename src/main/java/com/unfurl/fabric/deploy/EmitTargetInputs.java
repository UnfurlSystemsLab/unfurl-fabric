package com.unfurl.fabric.deploy;

import com.unfurl.deployment.policy.DeploymentTarget;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder: threads an emit's handoff paths into a deployment target's options, as the deploy emitter's backends read
 * them. {@code fabric deploy} and Studio's emit-plan preview (LLD-fabric-studio-api, <i>StudioEmitPlanPreview</i>) both
 * build their target here, so a preview emits with exactly the options a deploy would.
 *
 * <p>Rules, per target kind:
 * <ul>
 *   <li>{@code local-compose} receives every path: the signed contract and substrate profile (for a STRICT runtime
 *       profile), the runtime binding, the DCP runtime bundle, the Flow and Foundry deployment roots, the hosted
 *       components' jars ({@code componentArtifactsPath}, HLD-in-process-component-hosting §8), the trust keys, Flow's
 *       operator config and secrets mounts (HLD-needs-driven-identity §8), and each declared service's operator mounts
 *       (emitter HLD-E §4.0d).</li>
 *   <li>{@code k8s} receives only the composition-part inputs ({@code runtimeBindingPath}, {@code dcpRuntimeBundlePath},
 *       {@code trustKeysPath}): its declared parts need them, and it cannot deliver operator mounts
 *       (HLD-api-documentation-artifacts §4.1).</li>
 *   <li>Any other kind is returned unchanged.</li>
 * </ul>
 * A value already in the target's own options wins ({@code putIfAbsent}), so an operator's target file can override any
 * of them. Paths are written absolute. A path left unset is simply not threaded.
 */
public final class EmitTargetInputs {
    /** Target option: the signed Fabric contract. */
    public static final String CONTRACT = "fabricContractPath";
    /** Target option: the substrate profile. */
    public static final String PROFILE = "substrateProfilePath";
    /** Target option: the runtime-binding set. */
    public static final String RUNTIME_BINDING = "runtimeBindingPath";
    /** Target option: the signed DCP runtime bundle. */
    public static final String DCP_RUNTIME_BUNDLE = "dcpRuntimeBundlePath";
    /** Target option: Flow's workflow folder. */
    public static final String FLOW_WORKFLOWS = "flowWorkflowsPath";
    /** Target option: the Foundry deployment root. */
    public static final String FOUNDRY_DEPLOYMENT_ROOT = "foundryDeploymentRootPath";
    /** Target option: the folder of the hosted components' pinned jars. */
    public static final String COMPONENT_ARTIFACTS = "componentArtifactsPath";
    /** Target option: the public trust-key folder. */
    public static final String TRUST_KEYS = "trustKeysPath";
    /** Target option: Flow's operator config mount. */
    public static final String FLOW_CONFIG = "flowConfigPath";
    /** Target option: Flow's operator secrets mount. */
    public static final String FLOW_SECRETS = "flowSecretsPath";

    /** Target option prefix: a declared service's operator config mount, {@code serviceConfigPath.<name>}. */
    public static final String SERVICE_CONFIG_PREFIX = "serviceConfigPath.";
    /** Target option prefix: a declared service's operator secrets mount, {@code serviceSecretsPath.<name>}. */
    public static final String SERVICE_SECRETS_PREFIX = "serviceSecretsPath.";

    /**
     * Whether a target option names an operator path the emitter reads: Flow's workflows, the Foundry deployment root,
     * Flow's config and secrets mounts, or a declared service's mounts. These are the operator's deployment inputs, as
     * opposed to the generated handoff paths and to plain options such as images.
     *
     * @param key a target option key.
     * @return true for an operator path option.
     */
    public static boolean isOperatorPathOption(String key) {
        return FLOW_WORKFLOWS.equals(key) || FOUNDRY_DEPLOYMENT_ROOT.equals(key) || FLOW_CONFIG.equals(key)
                || FLOW_SECRETS.equals(key) || key.startsWith(SERVICE_CONFIG_PREFIX) || key.startsWith(SERVICE_SECRETS_PREFIX);
    }

    private final DeploymentTarget target;
    private Path contract;
    private Path profile;
    private Path runtimeBinding;
    private Path dcpRuntimeBundle;
    private Path flowWorkflows;
    private Path foundryDeploymentRoot;
    private Path componentArtifacts;
    private Path trustKeys;
    private Path flowConfig;
    private Path flowSecrets;
    private Map<String, String> serviceMounts = Map.of();

    private EmitTargetInputs(DeploymentTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("a deployment target is required");
        }
        this.target = target;
    }

    /**
     * Starts a builder for one target.
     *
     * @param target the operator's deployment target; its own options are kept and win over threaded paths.
     * @return the builder.
     */
    public static EmitTargetInputs forTarget(DeploymentTarget target) {
        return new EmitTargetInputs(target);
    }

    /** @param path the signed Fabric contract ({@code fabricContractPath}). */
    public EmitTargetInputs contract(Path path) { this.contract = path; return this; }

    /** @param path the substrate profile ({@code substrateProfilePath}). */
    public EmitTargetInputs profile(Path path) { this.profile = path; return this; }

    /** @param path the runtime-binding set ({@code runtimeBindingPath}). */
    public EmitTargetInputs runtimeBinding(Path path) { this.runtimeBinding = path; return this; }

    /** @param path the signed DCP runtime bundle ({@code dcpRuntimeBundlePath}). */
    public EmitTargetInputs dcpRuntimeBundle(Path path) { this.dcpRuntimeBundle = path; return this; }

    /** @param path Flow's workflow folder ({@code flowWorkflowsPath}). */
    public EmitTargetInputs flowWorkflows(Path path) { this.flowWorkflows = path; return this; }

    /** @param path the Foundry deployment root ({@code foundryDeploymentRootPath}). */
    public EmitTargetInputs foundryDeploymentRoot(Path path) { this.foundryDeploymentRoot = path; return this; }

    /** @param path the folder holding the hosted components' pinned jars ({@code componentArtifactsPath}). */
    public EmitTargetInputs componentArtifacts(Path path) { this.componentArtifacts = path; return this; }

    /** @param path the public trust-key folder ({@code trustKeysPath}). */
    public EmitTargetInputs trustKeys(Path path) { this.trustKeys = path; return this; }

    /** @param path Flow's operator config mount ({@code flowConfigPath}). */
    public EmitTargetInputs flowConfig(Path path) { this.flowConfig = path; return this; }

    /** @param path Flow's operator secrets mount ({@code flowSecretsPath}). */
    public EmitTargetInputs flowSecrets(Path path) { this.flowSecrets = path; return this; }

    /**
     * @param mounts declared services' operator mounts, already keyed {@code serviceConfigPath.<name>} and
     *               {@code serviceSecretsPath.<name>}; null means none.
     */
    public EmitTargetInputs serviceMounts(Map<String, String> mounts) {
        this.serviceMounts = mounts == null ? Map.of() : Map.copyOf(mounts);
        return this;
    }

    /**
     * Builds the target with the threaded options, by the kind rules above.
     *
     * @return the target with its options replaced, every other field kept.
     */
    public DeploymentTarget build() {
        if ("k8s".equals(target.kind())) {
            Map<String, String> options = new LinkedHashMap<>(target.options());
            putIfPresent(options, RUNTIME_BINDING, runtimeBinding);
            putIfPresent(options, DCP_RUNTIME_BUNDLE, dcpRuntimeBundle);
            putIfPresent(options, TRUST_KEYS, trustKeys);
            return withOptions(options);
        }
        if (!"local-compose".equals(target.kind())) {
            return target;
        }
        Map<String, String> options = new LinkedHashMap<>(target.options());
        putIfPresent(options, CONTRACT, contract);
        putIfPresent(options, PROFILE, profile);
        putIfPresent(options, RUNTIME_BINDING, runtimeBinding);
        putIfPresent(options, DCP_RUNTIME_BUNDLE, dcpRuntimeBundle);
        putIfPresent(options, FLOW_WORKFLOWS, flowWorkflows);
        putIfPresent(options, FOUNDRY_DEPLOYMENT_ROOT, foundryDeploymentRoot);
        putIfPresent(options, COMPONENT_ARTIFACTS, componentArtifacts);
        putIfPresent(options, TRUST_KEYS, trustKeys);
        putIfPresent(options, FLOW_CONFIG, flowConfig);
        putIfPresent(options, FLOW_SECRETS, flowSecrets);
        serviceMounts.forEach(options::putIfAbsent);
        return withOptions(options);
    }

    /** The target with its options replaced, every other field kept. */
    private DeploymentTarget withOptions(Map<String, String> options) {
        return new DeploymentTarget(target.kind(), target.environment(), target.region(), target.endpoint(), options,
                target.credentialRefs());
    }

    /** Writes an optional path as an absolute value, unless the target's own options already set the key. */
    private static void putIfPresent(Map<String, String> options, String key, Path path) {
        if (path != null) {
            options.putIfAbsent(key, path.toAbsolutePath().toString());
        }
    }
}
