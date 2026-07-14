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
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.dcp.validation.Severity;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        RuntimeBinding root = rootBinding(rootContract, childContracts, safe);
        List<RuntimeBinding> bindings = new ArrayList<>();
        bindings.add(root);
        for (CompositionContract child : childContracts) {
            bindings.add(childBinding(child, safe));
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
                URI.create(options.bindingIdPrefix() + ":" + options.tenant() + ":dev"),
                rootContract,
                DeploymentKind.IN_PROCESS,
                null,
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
     * Leaf assembler: creates a runtime binding for one child DCP contract.
     */
    private RuntimeBinding childBinding(CompositionContract contract, Options options) {
        String capability = contract.binding().providerCapability();
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("dcpType", "RUNTIME_BINDING_CHILD");
        extensions.put("level", "RUNTIME_CAPABILITY");
        extensions.put("sourceContractId", contract.contractId().toString());
        extensions.put("providerCapability", capability);
        return binding(
                childBindingId(contract, options),
                contract,
                deploymentKind(contract),
                endpointFor(capability, options),
                new Configuration(Map.of(
                        "capability", capability,
                        "endpoint_ref", "config://" + options.tenant() + "/dcp/" + slug(capability) + "/endpoint")),
                new RuntimeBindingMetadata(extensions),
                options);
    }

    /**
     * Binding factory: maps a DCP contract and environment options into a schema runtime binding.
     */
    private RuntimeBinding binding(
            URI bindingId,
            CompositionContract contract,
            DeploymentKind deploymentKind,
            URI baseUrl,
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
                        baseUrl == null ? new ConfigRef("config://" + options.tenant() + "/dcp/"
                                + slug(contract.binding().providerCapability()) + "/endpoint") : null,
                        new SecretRef("secret://" + options.tenant() + "/provider-access/" + instanceName(contract))),
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
     * Validation adapter: applies DCP runtime-binding tree validation before any YAML is emitted.
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
        List<Diagnostic> errors = report.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(diagnostic -> diagnostic.fieldPath() + ": " + diagnostic.message())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("runtime binding validation failed");
            throw new IllegalStateException(detail);
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
     * Deployment-kind strategy: converts the frozen contract transport binding mode into the DCP
     * runtime binding deployment enum.
     */
    private DeploymentKind deploymentKind(CompositionContract contract) {
        Object mode = contract.transport() == null ? null : contract.transport().details().get("bindingMode");
        String text = mode == null ? "" : mode.toString().toUpperCase(Locale.ROOT);
        return switch (text) {
            case "CONTAINER" -> DeploymentKind.CONTAINER;
            case "REMOTE_HTTP" -> DeploymentKind.REMOTE_SERVICE;
            case "SIDECAR" -> DeploymentKind.SIDECAR;
            default -> DeploymentKind.IN_PROCESS;
        };
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
     * Mapper factory: writes public DCP YAML using snake_case and no document marker.
     */
    private static ObjectMapper yamlMapper() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
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
            int timeoutMs
    ) {
        /** Default local-dev options used by the Flowfoundry runbook. */
        public static Options defaults() {
            return new Options(
                    Path.of("signed-contract.yaml"),
                    "",
                    "",
                    "",
                    "flowfoundry",
                    "local-dev",
                    "flowfoundry.local",
                    "urn:unfurl:runtime-binding:flowfoundry",
                    "http://flow:8080",
                    "http://foundry:7979",
                    30000);
        }

        /**
         * Normalizer: applies conservative local defaults without inventing signature metadata.
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
                    timeoutMs <= 0 ? defaults.timeoutMs : timeoutMs);
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
