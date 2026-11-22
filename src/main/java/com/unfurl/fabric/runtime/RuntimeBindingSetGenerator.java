package com.unfurl.fabric.runtime;

import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.documentation.CapabilityDocumentationValidator;
import com.unfurl.dcp.documentation.DocumentationGap;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.Configuration;
import com.unfurl.dcp.runtimebinding.ConsumerInstance;
import com.unfurl.dcp.runtimebinding.DeploymentControls;
import com.unfurl.dcp.runtimebinding.DeploymentKind;
import com.unfurl.dcp.runtimebinding.Lifecycle;
import com.unfurl.dcp.runtimebinding.ProviderInstance;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBindingCodec;
import com.unfurl.dcp.runtimebinding.RuntimeBindingMetadata;
import com.unfurl.dcp.runtimebinding.RuntimeBindingSet;
import com.unfurl.dcp.runtimebinding.RuntimeBindingValidator;
import com.unfurl.dcp.runtimebinding.RuntimePolicy;
import com.unfurl.dcp.runtimebinding.SecretRef;
import com.unfurl.dcp.runtimebinding.TargetEnvironment;
import com.unfurl.dcp.validation.SchemaValidationReport;
import com.unfurl.dcp.validation.Severity;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.SelectedCatalogEntries;
import com.unfurl.fabric.signing.SignedFabricContract;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builder/Composite Assembler: derives a DCP runtime-binding tree from Fabric's signed contract
 * closure.
 *
 * <p>The generator keeps aggregation inside DCP constructs: one aggregate binding references one
 * child binding per frozen child contract. Runtime-specific endpoint and secret wiring is attached
 * through runtime-binding fields, while ownership, trust, dependency, and invalidation semantics
 * remain frozen in the signed contracts.
 *
 * <p>Its inputs are the signed contract and the catalog entries it selected, resolved and pin-checked by
 * {@link SelectedCatalogEntries} (HLD-api-documentation-artifacts §4.0): the claims decide which offers are reached over
 * their own protocol and feed the documentation gate. A provider's declared service comes from the signed binding plan
 * (HLD-D §6.2b), the same entry the deploy emitter renders, so the endpoint and the service agree by construction.
 * Nothing is guessed: an HTTP-served provider without a base URL is refused (§4.1). When the composition contains an API
 * catalog ({@value #DOCUMENTATION_CATALOG_CAPABILITY}), its binding receives the documentation settings, and DCP's gap gate
 * runs over the policy first (§4.2, §4.3).
 */
public final class RuntimeBindingSetGenerator {
    /** The capability an API catalog offers; its provider's binding carries the documentation settings (§4.2). */
    public static final String DOCUMENTATION_CATALOG_CAPABILITY = "documentation.catalog";
    /** An HTTP-served provider with neither an operator base URL nor a declared service. */
    public static final String SERVICE_BASE_URL_MISSING = "SERVICE_BASE_URL_MISSING";
    /** A base URL for a claim URI that provides nothing in the composition. */
    public static final String SERVICE_BASE_URL_UNKNOWN = "SERVICE_BASE_URL_UNKNOWN";
    /** Documentation settings for a composition without an API catalog. */
    public static final String DOCUMENTATION_SETTINGS_WITHOUT_CATALOG = "DOCUMENTATION_SETTINGS_WITHOUT_CATALOG";
    /** The documentation policy would leave a documented capability without schemas (DCP HLD-C2 G.1). */
    public static final String DOCUMENTATION_GAP = "DOCUMENTATION_GAP";

    private final Clock clock;

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
    }

    /**
     * Generate, validate, and serialize a runtime-binding set for one signed contract closure.
     *
     * @param signed   signed Fabric contract closure; must include the aggregate contract.
     * @param selected the contract's selections resolved in the catalog it was compiled from.
     * @param options  environment-specific binding options.
     * @return generated YAML bytes.
     * @throws IllegalStateException naming every problem, each starting with its code: identity inputs,
     *                               {@value #SERVICE_BASE_URL_MISSING}, {@value #SERVICE_BASE_URL_UNKNOWN},
     *                               {@value #DOCUMENTATION_SETTINGS_WITHOUT_CATALOG}, {@value #DOCUMENTATION_GAP}, or a DCP
     *                               binding-validation failure; nothing is written
     */
    public byte[] generate(SignedFabricContract signed, SelectedCatalogEntries selected, Options options) {
        Assembled assembled = assemble(signed, selected, options);
        List<RuntimeBinding> children = withDocumentationSettings(assembled, selected);
        List<RuntimeBinding> bindings = new ArrayList<>();
        bindings.add(assembled.root());
        bindings.addAll(children);
        validate(assembled.root(), bindings, assembled.rootContract(), assembled.childContracts());
        // DCP owns the document format: its codec writes it, and its set type enforces unique binding and contract ids.
        return RuntimeBindingCodec.write(new RuntimeBindingSet(assembled.root().bindingId(), clock.instant(),
                assembled.options().sourceSignedContract().toString(), bindings));
    }

    /**
     * Value Object: the bindings of one contract before documentation settings: the aggregate root, the sorted children
     * and their bindings (same order), and the normalized options.
     */
    record Assembled(CompositionContract rootContract, List<CompositionContract> childContracts, RuntimeBinding root,
                     List<RuntimeBinding> childBindings, Options options) { }

    /**
     * Builder step shared by {@link #generate} and {@link DocumentationCheck}: checks the identity inputs and service base
     * URLs, then builds the root and child bindings exactly as they would be written, without documentation settings.
     */
    Assembled assemble(SignedFabricContract signed, SelectedCatalogEntries selected, Options options) {
        if (signed == null) {
            throw new IllegalArgumentException("signed contract is required");
        }
        if (selected == null) {
            throw new IllegalArgumentException("the contract's selections, resolved in its catalog, are required");
        }
        Options safe = options == null ? Options.defaults() : options.normalized();
        CompositionContract rootContract = signed.contract().contract();
        List<CompositionContract> childContracts = signed.contract().childContracts().stream()
                .sorted(Comparator.comparing(contract -> contract.contractId().toString()))
                .toList();
        BindingPlan plan = signed.contract().bindingPlan();
        requireIdentityInputs(childContracts, safe);
        requireServiceBases(childContracts, plan, selected, safe);
        RuntimeBinding root = rootBinding(rootContract, childContracts, safe);
        List<RuntimeBinding> children = new ArrayList<>();
        for (CompositionContract child : childContracts) {
            children.add(childBinding(child, plan, selected, safe));
        }
        return new Assembled(rootContract, childContracts, root, List.copyOf(children), safe);
    }

    /**
     * Strategy for the optional API catalog (HLD-api-documentation-artifacts §4.2, §4.3), decided by the signed contract
     * alone: with a {@value #DOCUMENTATION_CATALOG_CAPABILITY} provider, DCP's gap gate runs over the policy and that
     * provider's binding receives every documentation setting, defaults written explicitly; without one, nothing is added
     * and any documentation setting the operator gave is refused, so none is silently dropped.
     *
     * @return the child bindings, the catalog's with its settings
     */
    private List<RuntimeBinding> withDocumentationSettings(Assembled assembled, SelectedCatalogEntries selected) {
        DocumentationOptions documentation = assembled.options().documentation();
        Set<java.net.URI> catalogs = new TreeSet<>(Comparator.comparing(java.net.URI::toString));
        assembled.childContracts().stream()
                .filter(contract -> DOCUMENTATION_CATALOG_CAPABILITY.equals(contract.binding().providerCapability()))
                .forEach(contract -> catalogs.add(contract.contractId()));
        if (catalogs.isEmpty()) {
            if (documentation.given()) {
                throw new IllegalStateException(DOCUMENTATION_SETTINGS_WITHOUT_CATALOG + ": " + String.join(", ", documentation.givenFlags())
                        + " configure an API catalog, but the composition has no " + DOCUMENTATION_CATALOG_CAPABILITY
                        + " provider; add " + DOCUMENTATION_CATALOG_CAPABILITY + " to the needs, or leave these options out");
            }
            return assembled.childBindings();
        }
        List<DocumentationGap> gaps = new CapabilityDocumentationValidator().gaps(assembled.childContracts(), selected.claims(),
                assembled.childBindings(), documentation.policy());
        if (!gaps.isEmpty()) {
            throw new IllegalStateException(describeGaps(gaps));
        }
        Map<String, Object> settings = documentation.bindingValues();
        return assembled.childBindings().stream()
                .map(binding -> catalogs.contains(binding.contractId()) ? withValues(binding, settings) : binding)
                .toList();
    }

    /**
     * The {@value #DOCUMENTATION_GAP} refusal text: every gap with its contract, capability and provider, and both ways to
     * resolve one (HLD-api-documentation-artifacts §4.3).
     */
    static String describeGaps(List<DocumentationGap> gaps) {
        StringBuilder text = new StringBuilder(DOCUMENTATION_GAP + ": ");
        for (int i = 0; i < gaps.size(); i++) {
            DocumentationGap gap = gaps.get(i);
            if (i > 0) text.append("; ");
            text.append("contract ").append(gap.contractId()).append(" capability ").append(gap.capability())
                    .append(" provider ").append(gap.providerClaimUri()).append(" has no documentation schemas");
        }
        return text.append(". Ship the schemas in the provider's claim, or mark the capability private in "
                + "--documentation-visibility").toString();
    }

    /** A binding with extra {@code configuration.values}, keeping every other field. */
    private static RuntimeBinding withValues(RuntimeBinding binding, Map<String, Object> extra) {
        Configuration current = binding.configuration();
        Map<String, Object> values = new LinkedHashMap<>(current.values());
        values.putAll(extra);
        return new RuntimeBinding(binding.bindingId(), binding.contractId(), binding.contractVersion(), binding.targetEnvironment(),
                binding.providerInstance(), binding.consumerInstance(), binding.runtimePolicy(),
                new Configuration(values, current.configRefs(), current.secretRefs()), binding.deploymentControls(),
                binding.lifecycle(), binding.metadata());
    }

    /**
     * Precondition guard (HLD-api-documentation-artifacts §4.1): every HTTP-served provider has a base URL, from the
     * operator or its catalog block, and every operator base URL names a provider of the composition. Every problem is
     * reported in one message.
     */
    private void requireServiceBases(List<CompositionContract> childContracts, BindingPlan plan,
                                     SelectedCatalogEntries selected, Options options) {
        List<String> problems = new ArrayList<>();
        Set<java.net.URI> providers = new TreeSet<>(Comparator.comparing(java.net.URI::toString));
        Set<java.net.URI> missing = new TreeSet<>(Comparator.comparing(java.net.URI::toString));
        for (CompositionContract contract : childContracts) {
            java.net.URI provider = contract.parties().provider().claimUri();
            providers.add(provider);
            if (IdentityBindingRule.isIdentity(contract) || isHosted(contract, plan) || externalProtocol(contract, selected)) {
                continue;
            }
            if (RuntimeBindingValidator.isHttpServed(deploymentKind(contract, plan)) && baseFor(contract, plan, options).isEmpty()) {
                missing.add(provider);
            }
        }
        missing.forEach(provider -> problems.add(SERVICE_BASE_URL_MISSING + ": " + provider + " is served over HTTP but "
                + "has no base URL; give --service-base-url " + provider + "=<url>, or place it as a service of its own "
                + "(a containerized, Spring Boot or standalone shape) with binding.service_name and binding.service_port "
                + "in its catalog block"));
        options.serviceBaseUrls().keySet().stream().filter(claim -> !providers.contains(claim))
                .forEach(claim -> problems.add(SERVICE_BASE_URL_UNKNOWN + ": a base URL is given for " + claim
                        + ", which provides nothing in this composition"));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    /**
     * Base-URL strategy (HLD-api-documentation-artifacts §4.1): the operator's base URL for the provider's claim URI,
     * otherwise the {@code http://<name>:<port>} of the service the signed plan carries for this contract's entry
     * (HLD-D §6.2b), which is exactly the service the deploy emitter renders; otherwise none.
     */
    private static Optional<String> baseFor(CompositionContract contract, BindingPlan plan, Options options) {
        String explicit = options.serviceBaseUrls().get(contract.parties().provider().claimUri());
        if (explicit != null) {
            return Optional.of(explicit);
        }
        return planEntry(contract, plan).flatMap(com.unfurl.deployment.plan.BindingPlanEntry::service)
                .map(com.unfurl.deployment.domain.ServiceEndpoint::baseUrl);
    }

    /** The signed plan's entry for this contract's provider artifact and capability, when the plan has one. */
    private static Optional<com.unfurl.deployment.plan.BindingPlanEntry> planEntry(CompositionContract contract, BindingPlan plan) {
        if (plan == null || contract.metadata() == null || contract.metadata().extensions() == null) {
            return Optional.empty();
        }
        Object coordinates = contract.metadata().extensions().get("artifactCoordinates");
        String capability = contract.binding().providerCapability();
        return plan.entries().stream()
                .filter(entry -> entry.artifactCoordinates() != null && entry.artifactCoordinates().equals(coordinates)
                        && capability.equals(entry.capability()))
                .findFirst();
    }

    /**
     * Whether the provider's offer for this contract is reached over its own protocol (DCP HLD-C2 A.6,
     * {@code external_protocol}): such a provider never has a DCP endpoint, whatever its deployment kind.
     */
    static boolean externalProtocol(CompositionContract contract, SelectedCatalogEntries selected) {
        return providerOffer(contract, selected)
                .map(offer -> offer.offerInterface() != null && offer.offerInterface().interfaceKind() == InterfaceKind.EXTERNAL_PROTOCOL)
                .orElse(false);
    }

    /** The resolved provider claim's offer that this contract binds, when the provider was resolved. */
    private static Optional<Offer> providerOffer(CompositionContract contract, SelectedCatalogEntries selected) {
        String capability = contract.binding().providerCapability();
        return selected.forContract(contract).map(CatalogEntry::claimDescriptor)
                .flatMap(claim -> claim.claim().offers().stream().filter(offer -> capability.equals(offer.capability())).findFirst());
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
     * DCP endpoint, and hosted ones to {@link #hostedBinding}. A provider whose offer is {@code external_protocol} gets the
     * conventional endpoint reference instead of a DCP endpoint. Any other provider's endpoint is
     * {@code <base>/dcp/<capability>} when it has a base ({@link #baseFor}); {@link #requireServiceBases} has already
     * refused an HTTP-served provider without one.
     */
    private RuntimeBinding childBinding(CompositionContract contract, BindingPlan plan, SelectedCatalogEntries selected,
                                        Options options) {
        if (IdentityBindingRule.isIdentity(contract)) {
            return identityBinding(contract, plan, options);
        }
        if (isHosted(contract, plan)) {
            return hostedBinding(contract, options);
        }
        String capability = contract.binding().providerCapability();
        boolean external = externalProtocol(contract, selected);
        URI baseUrl = external ? null : baseFor(contract, plan, options)
                .map(base -> URI.create(base + RuntimeBindingValidator.DCP_PATH_PREFIX + capability)).orElse(null);
        return binding(
                childBindingId(contract, options),
                contract,
                deploymentKind(contract, plan),
                baseUrl,
                external ? conventionalEndpointRef(contract, options) : null,
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
     * endpoint as the provider endpoint reference, the host's client credentials as the credentials reference, and each
 * service-credentials input as a {@code configuration.secret_refs} entry keyed by the service's claim URI. The inputs
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
        // Each service that calls others as itself: its client credentials, a typed secret reference (HLD-C2 secret_refs).
        Map<String, SecretRef> secretRefs = new LinkedHashMap<>();
        identity.serviceCredentials().forEach((service, ref) -> secretRefs.put(service, new SecretRef(ref)));
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
                new Configuration(values, refs, secretRefs),
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
                IdentityBindingRule.serviceCredentialProblems(identity.serviceCredentials(), protectedServices.get())
                        .forEach(problem -> problems.add("service credentials: " + problem));
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

    /**
     * Plan lookup: the binding mode of the plan entry for this contract's provider artifact and capability, or null when the
     * plan is absent or has no such entry.
     */
    private Object planBindingMode(CompositionContract contract, BindingPlan plan) {
        return planEntry(contract, plan).map(entry -> entry.bindingMode().name()).orElse(null);
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
     * Value Object: the operator-supplied settings of the identity-provider binding (HLD-needs-driven-identity §5, §6).
     * Fabric never defaults or derives any of them. Every field is required when the composition has an
     * {@code identity-context} contract, and none may be given when it has not. Audiences map each protected service's
     * claim URI to its token audience and are kept sorted by key so generated YAML is deterministic. Service credentials map
     * each service that calls others as itself (Foundry calling Flow's tools, Flow creating its authoring agent in Foundry) to
     * the {@code secret://} reference of its client credentials; they are optional, sorted the same way.
     */
    public record IdentityOptions(
            String issuer,
            Map<String, String> audiences,
            String tenantClaim,
            String rolesClaim,
            String permissionsClaim,
            String tokenEndpointRef,
            String verificationKeysRef,
            String clientCredentialsRef,
            Map<String, String> serviceCredentials
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
            Map<String, String> credentials = new TreeMap<>();
            if (serviceCredentials != null) {
                serviceCredentials.forEach((service, ref) -> {
                    if (trimmed(service) != null && trimmed(ref) != null) {
                        credentials.put(service.trim(), ref.trim());
                    }
                });
            }
            serviceCredentials = Collections.unmodifiableMap(credentials);
        }

        /** Null Object: no identity input supplied (the default; valid only for a composition without identity). */
        public static IdentityOptions none() {
            return new IdentityOptions(null, Map.of(), null, null, null, null, null, null, Map.of());
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
            return missing().size() == 7 && audiences.isEmpty() && serviceCredentials.isEmpty();
        }

        /** Text helper: trims, mapping blank to null so "supplied" means non-blank. */
        private static String trimmed(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * Options value: environment-specific inputs for runtime-binding generation.
     *
     * @param serviceBaseUrls        operator base URLs by provider claim URI ({@link ServiceBaseUrls}); they override the
     *                               service a provider's catalog block declares. Never guessed: empty by default.
     * @param documentation          the API catalog's documentation settings; {@link DocumentationOptions#none()} when none
     *                               was given
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
            Map<URI, String> serviceBaseUrls,
            int timeoutMs,
            IdentityOptions identity,
            Map<URI, Configuration> componentConfiguration,
            DocumentationOptions documentation
    ) {
        /** Validating constructor: maps are copied and never null; no documentation settings unless given. */
        public Options {
            serviceBaseUrls = serviceBaseUrls == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(serviceBaseUrls));
            componentConfiguration = componentConfiguration == null ? Map.of() : Map.copyOf(componentConfiguration);
            documentation = documentation == null ? DocumentationOptions.none() : documentation;
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
                    Map.of(),
                    30000,
                    IdentityOptions.none(),
                    Map.of(),
                    DocumentationOptions.none());
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
                    serviceBaseUrls,
                    timeoutMs <= 0 ? defaults.timeoutMs : timeoutMs,
                    identity == null ? IdentityOptions.none() : identity,
                    componentConfiguration,
                    documentation);
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
