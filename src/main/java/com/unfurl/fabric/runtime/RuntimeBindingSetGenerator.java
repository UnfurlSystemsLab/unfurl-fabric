package com.unfurl.fabric.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.dcp.runtimebinding.ConsumerInstance;
import com.unfurl.dcp.runtimebinding.DeploymentControls;
import com.unfurl.dcp.runtimebinding.DeploymentKind;
import com.unfurl.dcp.runtimebinding.Lifecycle;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBindingMetadata;
import com.unfurl.dcp.runtimebinding.RuntimeBindingValidator;
import com.unfurl.dcp.runtimebinding.RuntimePolicy;
import com.unfurl.dcp.runtimebinding.SecretRef;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.dcp.validation.Severity;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.fabric.signing.SignedFabricContract;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builder/Composite Assembler: derives a DCP runtime-binding tree from Fabric's signed contract
 * closure.
 *
 * <p>The generator keeps aggregation inside DCP constructs: one aggregate binding references one
 * child binding per frozen child contract. Runtime-specific endpoint and secret wiring is attached
 * through runtime-binding fields, while ownership, trust, dependency, and invalidation semantics
 * remain frozen in the signed contracts.
 */
public final class RuntimeBindingSetGenerator {
    private final Clock clock;
    private final ObjectMapper yamlMapper;

    /** Production constructor: stamps generated binding sets with the current UTC time. */
    public RuntimeBindingSetGenerator() {
        this(Clock.systemUTC());
    }

    /**
     * Test constructor: injects the time source used for deterministic generated-at values.
     *
     * @param clock time source; defaults to system UTC when null.
     */
    public RuntimeBindingSetGenerator(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.yamlMapper = yamlMapper();
    }

    /**
     * Generate, validate, and serialize a runtime-binding set for one signed contract closure.
     *
     * @param signed signed Fabric contract closure; must include the aggregate contract.
     * @param options environment-specific binding options.
     * @return generated YAML bytes.
     */
    public byte[] generate(SignedFabricContract signed, Options options) {
        if (signed == null) {
            throw new IllegalArgumentException("signed contract is required");
        }
        Options safe = options == null ? Options.defaults() : options.normalized();
        CompositionContract rootContract = signed.contract().contract();
        List<CompositionContract> childContracts = signed.contract().childContracts().stream()
                .sorted(Comparator.comparing(contract -> contract.contractId().toString()))
                .toList();
        requireIdentityInputs(childContracts, safe);
        RuntimeBinding root = rootBinding(rootContract, childContracts, safe);
        List<RuntimeBinding> bindings = new ArrayList<>();
        bindings.add(root);
        for (CompositionContract child : childContracts) {
            bindings.add(childBinding(child, signed.contract().bindingPlan(), safe));
        }
        validate(root, bindings, rootContract, childContracts);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("runtime_binding_set", Map.of(
                "root_binding_id", root.bindingId(),
                "generated_at", clock.instant(),
                "source_signed_contract", safe.sourceSignedContract().toString(),
                "runtime_bindings", bindings));
        try {
            return yamlMapper.writeValueAsBytes(wrapper);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to write runtime binding set: " + ex.getMessage(), ex);
        }
    }

    /**
     * Composite assembler: creates the aggregate binding whose metadata references every child
     * binding by id.
     */
    private RuntimeBinding rootBinding(
            CompositionContract rootContract,
            List<CompositionContract> childContracts,
            Options options
    ) {
        List<Map<String, String>> contains = childContracts.stream()
                .map(child -> Map.of("bindingId", childBindingId(child, options).toString()))
                .toList();
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("dcpType", "RUNTIME_BINDING_AGGREGATE");
        extensions.put("level", "RUNTIME_ASSEMBLY");
        extensions.put("sourceContractId", rootContract.contractId().toString());
        extensions.put("providerCapability", rootContract.binding().providerCapability());
        extensions.put(RuntimeBindingMetadata.EXT_CONTAINS, contains);
        return binding(
                URI.create(options.bindingIdPrefix() + ":" + options.tenant() + ":" + slug(options.environment())),
                rootContract,
                DeploymentKind.IN_PROCESS,
                null,
                conventionalEndpointRef(rootContract, options),
                conventionalCredentialsRef(rootContract, options),
                new Configuration(Map.of(
                        "signed_contract_ref", "config://" + options.tenant() + "/contracts/signed-contract",
                        "substrate_profile_ref", "config://" + options.tenant() + "/substrate-profile",
                        "signed_contract_artifact_sha256", options.signedContractSha256(),
                        "signed_contract_canonical_hash", options.signedContractCanonicalHash(),
                        "signer_key_fingerprint", options.signerKeyFingerprint())),
                new RuntimeBindingMetadata(extensions),
                options);
    }

    /**
     * Leaf assembler: creates a runtime binding for one child DCP contract. Identity-provider contracts are delegated to
     * {@link #identityBinding}, because the customer IdP is an external system with operator-supplied settings rather than a
     * Flow or Foundry endpoint.
     */
    private RuntimeBinding childBinding(CompositionContract contract, BindingPlan plan, Options options) {
        if (IdentityBindingRule.isIdentity(contract)) {
            return identityBinding(contract, plan, options);
        }
        if (isHosted(contract, plan)) {
            return hostedBinding(contract, options);
        }
        String capability = contract.binding().providerCapability();
        URI baseUrl = endpointFor(capability, options);
        return binding(
                childBindingId(contract, options),
                contract,
                deploymentKind(contract, plan),
                baseUrl,
                null,
                conventionalCredentialsRef(contract, options),
                new Configuration(Map.of(
                        "capability", capability,
                        "endpoint_ref", "config://" + options.tenant() + "/dcp/" + slug(capability) + "/endpoint")),
                childMetadata(contract),
                options);
    }

    /**
     * Leaf assembler for the identity-provider binding (HLD-needs-driven-identity §5, §6). It carries the operator's issuer,
     * audiences and claim names as values, the token endpoint and verification keys as typed config references, the token
     * endpoint as the provider endpoint reference, and the host's client credentials as the credentials reference. The inputs
     * are complete here, because {@link #requireIdentityInputs} refuses the run otherwise. It never has a base URL: the
     * identity provider is reached only through references.
     */
    private RuntimeBinding identityBinding(CompositionContract contract, BindingPlan plan, Options options) {
        IdentityOptions identity = options.identity();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("capability", contract.binding().providerCapability());
        values.put("issuer", identity.issuer());
        values.put("audiences", identity.audiences());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tenant", identity.tenantClaim());
        claims.put("roles", identity.rolesClaim());
        claims.put("permissions", identity.permissionsClaim());
        values.put("claims", claims);
        ConfigRef endpointRef = new ConfigRef(identity.tokenEndpointRef());
        Map<String, ConfigRef> refs = new LinkedHashMap<>();
        refs.put("token_endpoint", endpointRef);
        refs.put("verification_keys", new ConfigRef(identity.verificationKeysRef()));
        return binding(
                childBindingId(contract, options),
                contract,
                deploymentKind(contract, plan),
                null,
                endpointRef,
                new SecretRef(identity.clientCredentialsRef()),
                new Configuration(values, refs),
                childMetadata(contract),
                options);
    }

    /**
     * Precondition guard (HLD-needs-driven-identity §6): fails before any binding is built when the identity inputs and the
     * composition disagree. The environment plays no part. A composition with an {@code identity-context} contract has
     * identity because its needs required it, and every capability in the needs is mandatory, so every identity input is
     * required. A composition without one has no identity, so inputs are refused rather than silently dropped. The audiences
     * must cover exactly the protected services the compiler recorded on the identity contract
     * ({@link IdentityBindingRule#protectedServices}), and the issuer must pass {@link IdentityBindingRule#secureIssuer}.
     * Every problem is reported in one message.
     */
    private void requireIdentityInputs(List<CompositionContract> childContracts, Options options) {
        IdentityOptions identity = options.identity();
        List<CompositionContract> identityContracts = childContracts.stream().filter(IdentityBindingRule::isIdentity).toList();
        if (identityContracts.isEmpty()) {
            if (!identity.absent()) {
                throw new IllegalStateException("identity inputs were supplied, but the composition has no "
                        + IdentityBindingRule.CAPABILITY + " contract; add " + IdentityBindingRule.CAPABILITY
                        + " to the needs so the identity provider component is selected, or leave the identity inputs out");
            }
            return;
        }
        List<String> problems = new ArrayList<>();
        List<String> missing = identity.missing();
        if (!missing.isEmpty()) {
            problems.add("missing identity inputs: " + String.join(", ", missing));
        }
        if (identity.issuer() != null && !IdentityBindingRule.secureIssuer(identity.issuer())) {
            problems.add("identity issuer '" + identity.issuer()
                    + "' must be an absolute https URI (http is allowed only on localhost)");
        }
        for (CompositionContract contract : identityContracts) {
            var protectedServices = IdentityBindingRule.protectedServices(contract);
            if (protectedServices.isEmpty()) {
                problems.add("the signed contract does not record which services need " + IdentityBindingRule.CAPABILITY
                        + "; compile the composition again");
            } else {
                IdentityBindingRule.coverageProblems(identity.audiences().keySet(), protectedServices.get())
                        .forEach(problem -> problems.add("audiences: " + problem));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("the composition's needs require " + IdentityBindingRule.CAPABILITY
                    + ", so its identity binding is mandatory; " + String.join("; ", problems));
        }
    }

    /** Metadata factory: the child-binding extension block shared by every leaf binding. */
    private RuntimeBindingMetadata childMetadata(CompositionContract contract) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("dcpType", "RUNTIME_BINDING_CHILD");
        extensions.put("level", "RUNTIME_CAPABILITY");
        extensions.put("sourceContractId", contract.contractId().toString());
        extensions.put("providerCapability", contract.binding().providerCapability());
        return new RuntimeBindingMetadata(extensions);
    }

    /** Convention: the tenant-scoped endpoint reference for a contract whose provider has no base URL. */
    private ConfigRef conventionalEndpointRef(CompositionContract contract, Options options) {
        return new ConfigRef("config://" + options.tenant() + "/dcp/" + slug(contract.binding().providerCapability()) + "/endpoint");
    }

    /** Convention: the tenant-scoped provider-access secret reference, resolved by the deployment environment. */
    private SecretRef conventionalCredentialsRef(CompositionContract contract, Options options) {
        return new SecretRef("secret://" + options.tenant() + "/provider-access/" + instanceName(contract));
    }

    /**
     * Binding factory: maps a DCP contract and environment options into a schema runtime binding. The provider endpoint is
     * either a base URL or an endpoint reference; credentials are always a reference.
     */
    private RuntimeBinding binding(
            URI bindingId,
            CompositionContract contract,
            DeploymentKind deploymentKind,
            URI baseUrl,
            ConfigRef endpointRef,
            SecretRef credentialsRef,
            Configuration configuration,
            RuntimeBindingMetadata metadata,
            Options options
    ) {
        return new RuntimeBinding(
                bindingId,
                contract.contractId(),
                contract.contractVersion(),
                new TargetEnvironment(options.environment()),
                new ProviderInstance(
                        contract.parties().provider().claimUri(),
                        contract.parties().provider().claimVersion(),
                        instanceName(contract),
                        deploymentKind,
                        baseUrl,
                        baseUrl == null ? endpointRef : null,
                        credentialsRef),
                new ConsumerInstance(
                        contract.parties().consumer().claimUri(),
                        contract.parties().consumer().claimVersion(),
                        "flowfoundry-host"),
                new RuntimePolicy(true, options.timeoutMs(), options.telemetryNamespace(), true),
                configuration,
                new DeploymentControls(Map.of(
                        "rollout_strategy", "manual",
                        "min_instances", 1,
                        "max_instances", 1)),
                new Lifecycle(true),
                metadata);
    }

    /**
     * Validation adapter: applies DCP runtime-binding tree validation, then {@link IdentityBindingRule} to each identity
     * binding, before any YAML is emitted.
     */
    private void validate(
            RuntimeBinding root,
            List<RuntimeBinding> bindings,
            CompositionContract rootContract,
            List<CompositionContract> childContracts
    ) {
        Map<URI, RuntimeBinding> bindingsById = new LinkedHashMap<>();
        bindings.forEach(binding -> bindingsById.put(binding.bindingId(), binding));
        Map<URI, CompositionContract> contractsById = new LinkedHashMap<>();
        contractsById.put(rootContract.contractId(), rootContract);
        childContracts.forEach(contract -> contractsById.put(contract.contractId(), contract));
        SchemaValidationReport report = new RuntimeBindingValidator().validateTree(root, bindingsById, contractsById);
        List<String> errors = new ArrayList<>(report.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
                .map(diagnostic -> diagnostic.fieldPath() + ": " + diagnostic.message())
                .toList());
        IdentityBindingRule identityRule = new IdentityBindingRule();
        for (RuntimeBinding binding : bindings) {
            CompositionContract contract = contractsById.get(binding.contractId());
            if (IdentityBindingRule.isIdentity(contract)) {
                identityRule.violations(binding, contract).forEach(violation -> errors.add(binding.bindingId() + " " + violation));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("; ", errors));
        }
    }

    /**
     * Endpoint strategy: Flow capabilities route to Flow; AI/provider/tool capabilities route to
     * Foundry's DCP endpoint surface.
     */
    private URI endpointFor(String capability, Options options) {
        String base = capability != null && capability.startsWith("workflow.")
                ? options.flowBaseUrl()
                : options.foundryBaseUrl();
        return URI.create(trimTrailingSlash(base) + "/dcp/" + capability);
    }

    /**
     * Deployment-kind strategy: converts the binding mode of the shape Fabric selected for the contract's provider into the
     * DCP runtime-binding deployment enum. The binding plan is authoritative because a component that supports several shapes
     * (for example the Keycloak adapter: container or external) keeps only its catalog default in the contract transport. The
     * transport mode is used only when the signed closure carries no plan entry for the provider.
     */
    private DeploymentKind deploymentKind(CompositionContract contract, BindingPlan plan) {
        Object mode = planBindingMode(contract, plan);
        if (mode == null) {
            mode = contract.transport() == null ? null : contract.transport().details().get("bindingMode");
        }
        String text = mode == null ? "" : mode.toString().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "CONTAINER" -> DeploymentKind.CONTAINER;
            case "REMOTE_HTTP" -> DeploymentKind.REMOTE_SERVICE;
            case "SIDECAR" -> DeploymentKind.SIDECAR;
            // A managed adapter fronts a customer-operated external system (for example the identity provider).
            case "MANAGED_ADAPTER" -> DeploymentKind.EXTERNAL_SAAS;
            default -> DeploymentKind.IN_PROCESS;
        };
    }

    /**
     * Plan lookup: the binding mode of the plan entry for this contract's provider artifact and capability, or null when the
     * plan is absent or has no such entry.
     */
    /**
     * Whether a child contract's provider is hosted in-process by another runtime: its binding-plan entry has a host
     * (HLD-D §6.2a). Such a contract already names that host as consumer (HLD-in-process-component-hosting §4).
     */
    private boolean isHosted(CompositionContract contract, BindingPlan plan) {
        if (plan == null || contract.metadata() == null) return false;
        Object coordinates = contract.metadata().extensions().get("artifactCoordinates");
        String capability = contract.binding().providerCapability();
        return plan.entries().stream().anyMatch(entry -> entry.artifactCoordinates().equals(coordinates)
                && capability.equals(entry.capability()) && entry.host().isPresent());
    }

    /**
     * Leaf assembler for a hosted in-process provider (HLD-in-process-component-hosting §6): {@code IN_PROCESS} with no
     * base URL and no endpoint reference, consumed by the host (whose instance name is its claim slug), carrying only the
     * deployment layer of the component's configuration. Packaged defaults stay in the pinned jar, where the host reads
     * them. The deployment values were validated against the packaged schema before generation.
     */
    private RuntimeBinding hostedBinding(CompositionContract contract, Options options) {
        Configuration deployment = options.componentConfiguration()
                .getOrDefault(contract.parties().provider().claimUri(), new Configuration(Map.of()));
        RuntimeBinding binding = binding(
                childBindingId(contract, options),
                contract,
                DeploymentKind.IN_PROCESS,
                null,
                null,
                conventionalCredentialsRef(contract, options),
                deployment,
                childMetadata(contract),
                options);
        return new RuntimeBinding(binding.bindingId(), binding.contractId(), binding.contractVersion(), binding.targetEnvironment(),
                binding.providerInstance(),
                new ConsumerInstance(contract.parties().consumer().claimUri(), contract.parties().consumer().claimVersion(),
                        slug(contract.parties().consumer().claimUri().toString())),
                binding.runtimePolicy(), binding.configuration(), binding.deploymentControls(), binding.lifecycle(),
                binding.metadata());
    }

    private Object planBindingMode(CompositionContract contract, BindingPlan plan) {
        if (plan == null || contract.metadata() == null || contract.metadata().extensions() == null) {
            return null;
        }
        Object coordinates = contract.metadata().extensions().get("artifactCoordinates");
        String capability = contract.binding().providerCapability();
        return plan.entries().stream()
                .filter(entry -> entry.artifactCoordinates() != null && entry.artifactCoordinates().equals(coordinates)
                        && capability.equals(entry.capability()))
                .map(entry -> entry.bindingMode().name())
                .findFirst()
                .orElse(null);
    }

    /**
     * Stable id helper: derives a readable binding id from capability plus child contract id hash.
     */
    private URI childBindingId(CompositionContract contract, Options options) {
        return URI.create(options.bindingIdPrefix()
                + ":" + slug(contract.binding().providerCapability())
                + ":" + shortHash(contract.contractId().toString()));
    }

    /**
     * Instance-name helper: keeps provider instance names deterministic and human-readable.
     */
    private String instanceName(CompositionContract contract) {
        return slug(contract.parties().provider().claimUri().toString());
    }

    /** Text helper: trims one trailing slash from a base URL. */
    private String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    /** Text helper: generates DCP-friendly lowercase slugs. */
    private static String slug(String value) {
        String slug = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "local" : slug;
    }

    /** Hash helper: returns the first twelve hex chars of a SHA-256 digest. */
    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Mapper factory: writes public DCP YAML using snake_case and no document marker. Quotes are minimized, but number-like
     * strings stay quoted so untyped configuration values (an audience such as {@code "1234"}) are read back as strings by
     * Flow, Foundry and {@code assembly-bind}'s DCP validation.
     */
    private static ObjectMapper yamlMapper() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS);
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    /**
     * Value Object: the operator-supplied settings of the identity-provider binding (HLD-needs-driven-identity §5, §6).
     * Fabric never defaults or derives any of them. Every field is required when the composition has an
     * {@code identity-context} contract, and none may be given when it has not. Audiences map each protected service's
     * claim URI to its token audience and are kept sorted by key so generated YAML is deterministic.
     */
    public record IdentityOptions(
            String issuer,
            Map<String, String> audiences,
            String tenantClaim,
            String rolesClaim,
            String permissionsClaim,
            String tokenEndpointRef,
            String verificationKeysRef,
            String clientCredentialsRef
    ) {
        /** Canonicalizes: trims text, drops blank audience entries, and freezes audiences in key order. */
        public IdentityOptions {
            issuer = trimmed(issuer);
            tenantClaim = trimmed(tenantClaim);
            rolesClaim = trimmed(rolesClaim);
            permissionsClaim = trimmed(permissionsClaim);
            tokenEndpointRef = trimmed(tokenEndpointRef);
            verificationKeysRef = trimmed(verificationKeysRef);
            clientCredentialsRef = trimmed(clientCredentialsRef);
            Map<String, String> sorted = new TreeMap<>();
            if (audiences != null) {
                audiences.forEach((service, audience) -> {
                    if (trimmed(service) != null && trimmed(audience) != null) {
                        sorted.put(service.trim(), audience.trim());
                    }
                });
            }
            audiences = Collections.unmodifiableMap(sorted);
        }

        /** Null Object: no identity input supplied (the default; valid only for a composition without identity). */
        public static IdentityOptions none() {
            return new IdentityOptions(null, Map.of(), null, null, null, null, null, null);
        }

        /**
         * Names of the scalar inputs that are missing, in declaration order. Audiences are not listed: whether they are
         * complete depends on the composition's protected services, which {@link IdentityBindingRule#coverageProblems}
         * checks.
         */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (issuer == null) missing.add("issuer");
            if (tenantClaim == null) missing.add("tenantClaim");
            if (rolesClaim == null) missing.add("rolesClaim");
            if (permissionsClaim == null) missing.add("permissionsClaim");
            if (tokenEndpointRef == null) missing.add("tokenEndpointRef");
            if (verificationKeysRef == null) missing.add("verificationKeysRef");
            if (clientCredentialsRef == null) missing.add("clientCredentialsRef");
            return List.copyOf(missing);
        }

        /** True when no identity input at all is present, audiences included. */
        public boolean absent() {
            return missing().size() == 7 && audiences.isEmpty();
        }

        /** Text helper: trims, mapping blank to null so "supplied" means non-blank. */
        private static String trimmed(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * Options value: environment-specific inputs for runtime-binding generation.
     */
    public record Options(
            Path sourceSignedContract,
            String signedContractSha256,
            String signedContractCanonicalHash,
            String signerKeyFingerprint,
            String tenant,
            String environment,
            String telemetryNamespace,
            String bindingIdPrefix,
            String flowBaseUrl,
            String foundryBaseUrl,
            int timeoutMs,
            IdentityOptions identity,
            Map<URI, Configuration> componentConfiguration
    ) {
        /** Validating constructor: the per-component configuration map is copied and never null. */
        public Options {
            componentConfiguration = componentConfiguration == null ? Map.of() : Map.copyOf(componentConfiguration);
        }

        /** Compatibility constructor: no hosted component configuration. */
        public Options(Path sourceSignedContract, String signedContractSha256, String signedContractCanonicalHash,
                       String signerKeyFingerprint, String tenant, String environment, String telemetryNamespace,
                       String bindingIdPrefix, String flowBaseUrl, String foundryBaseUrl, int timeoutMs,
                       IdentityOptions identity) {
            this(sourceSignedContract, signedContractSha256, signedContractCanonicalHash, signerKeyFingerprint, tenant,
                    environment, telemetryNamespace, bindingIdPrefix, flowBaseUrl, foundryBaseUrl, timeoutMs, identity, Map.of());
        }

        /** Default local-dev options used by the Flowfoundry runbook's signed support envelope; no identity inputs. */
        public static Options defaults() {
            return new Options(
                    Path.of("signed-compiled-contract.yaml"),
                    "",
                    "",
                    "",
                    "flowfoundry",
                    "local-dev",
                    "flowfoundry.local",
                    "urn:unfurl:runtime-binding:flowfoundry",
                    "http://flow:8080",
                    "http://foundry:7979",
                    30000,
                    IdentityOptions.none());
        }

        /**
         * Normalizer: applies conservative local defaults without inventing signature metadata or identity settings.
         */
        Options normalized() {
            Options defaults = defaults();
            return new Options(
                    sourceSignedContract == null ? defaults.sourceSignedContract : sourceSignedContract,
                    blankToDefault(signedContractSha256, defaults.signedContractSha256),
                    blankToDefault(signedContractCanonicalHash, defaults.signedContractCanonicalHash),
                    blankToDefault(signerKeyFingerprint, defaults.signerKeyFingerprint),
                    blankToDefault(tenant, defaults.tenant),
                    blankToDefault(environment, defaults.environment),
                    blankToDefault(telemetryNamespace, defaults.telemetryNamespace),
                    blankToDefault(bindingIdPrefix, defaults.bindingIdPrefix),
                    blankToDefault(flowBaseUrl, defaults.flowBaseUrl),
                    blankToDefault(foundryBaseUrl, defaults.foundryBaseUrl),
                    timeoutMs <= 0 ? defaults.timeoutMs : timeoutMs,
                    identity == null ? IdentityOptions.none() : identity,
                    componentConfiguration);
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
