package com.unfurl.fabric.runtime;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.runtimebinding.ConfigRef;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.fabric.compile.ContractCompiler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Specification: what a complete identity binding is (HLD-needs-driven-identity §5), including that its audiences cover
 * exactly the composition's protected services, the components whose claims depend on {@code identity-context}.
 *
 * <p>Identity is not tied to an environment. A composition contains an {@code identity-context} contract only when its
 * needs required that capability (Fabric selects components only for required capabilities), and every capability in the
 * needs is mandatory. So whenever the composition has an identity contract, its binding must be complete, in every
 * environment; a composition without one has no identity requirement at all. DCP itself stays general: it only requires
 * that every child contract is bound, and this rule adds what the Unfurl identity contract needs.
 *
 * <p>Used by {@link RuntimeBindingSetGenerator} before any YAML is written and by binding admission
 * ({@code assembly-bind}, {@code assembly-verify-binding}), so a hand-edited binding cannot drop a field.
 */
public final class IdentityBindingRule {
    /** Capability of the identity provider component's offer (the Keycloak adapter). */
    public static final String CAPABILITY = "identity-context";
    /** Claim names the binding must map. */
    private static final List<String> CLAIMS = List.of("tenant", "roles", "permissions");
    /** Configuration references the binding must carry. */
    private static final List<String> REFS = List.of("token_endpoint", "verification_keys");
    /** Hosts allowed to use plain http: a developer machine's loopback interface, as OAuth allows (RFC 8252 §8.3). */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /**
     * Predicate: whether a contract binds the identity provider's offer.
     *
     * @param contract a child contract of the composition.
     * @return true for an {@code identity-context} contract.
     */
    public static boolean isIdentity(CompositionContract contract) {
        return contract != null && contract.binding() != null && CAPABILITY.equals(contract.binding().providerCapability());
    }

    /**
     * Issuer check: an absolute URI with a host, using {@code https}, or {@code http} only on a loopback host so a Keycloak
     * container on a developer machine works without TLS.
     *
     * @param issuer the issuer text.
     * @return true when the issuer is acceptable.
     */
    public static boolean secureIssuer(String issuer) {
        try {
            URI uri = URI.create(issuer.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getScheme() == null) return false;
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && LOOPBACK_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Protected-service lookup: the claim URIs of the selected components that depend on {@code identity-context}, as the
     * compiler recorded them on the identity contract ({@link ContractCompiler#EXT_NEEDED_BY}). These are the services that
     * verify tokens and so need an audience; the identity provider itself and Flow (which exchanges but does not verify) are
     * not among them.
     *
     * @param identityContract the {@code identity-context} child contract.
     * @return the sorted protected services, or empty when the contract predates the record (the caller must refuse it).
     */
    public static Optional<List<String>> protectedServices(CompositionContract identityContract) {
        Object recorded = identityContract == null || identityContract.metadata() == null
                || identityContract.metadata().extensions() == null
                ? null : identityContract.metadata().extensions().get(ContractCompiler.EXT_NEEDED_BY);
        if (!(recorded instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            return Optional.empty();
        }
        return Optional.of(list.stream().map(String.class::cast).sorted().toList());
    }

    /**
     * Coverage check: the audience keys must be exactly the protected services, one audience each and nothing else.
     *
     * @param audienceKeys      the claim URIs the operator gave audiences for.
     * @param protectedServices the claim URIs that need one (see {@link #protectedServices}).
     * @return one problem per uncovered service and per extra key, in sorted order; empty when covered exactly.
     */
    public static List<String> coverageProblems(Set<String> audienceKeys, List<String> protectedServices) {
        List<String> problems = new ArrayList<>();
        protectedServices.stream().filter(service -> !audienceKeys.contains(service)).sorted()
                .forEach(service -> problems.add("no audience for " + service + ", which needs " + CAPABILITY));
        audienceKeys.stream().filter(key -> !protectedServices.contains(key)).sorted()
                .forEach(key -> problems.add(key + " does not need " + CAPABILITY + ", so it takes no audience"
                        + " (services that need it: " + (protectedServices.isEmpty() ? "none" : String.join(", ", protectedServices)) + ")"));
        return problems;
    }

    /**
     * Completeness check of one identity binding: issuer (see {@link #secureIssuer}), audiences covering exactly the
     * protected services of its contract, the tenant/roles/permissions claim names, the token-endpoint and verification-key
     * references, and the host's client-credential reference.
     *
     * @param binding          the runtime binding of an identity contract.
     * @param identityContract that binding's {@code identity-context} contract, which records the protected services.
     * @return one {@code field path: problem} line per violation, at paths relative to the binding; empty when complete.
     */
    public List<String> violations(RuntimeBinding binding, CompositionContract identityContract) {
        List<String> violations = new ArrayList<>();
        Map<String, Object> values = binding.configuration() == null ? Map.of() : binding.configuration().values();
        Map<String, ConfigRef> refs = binding.configuration() == null ? Map.of() : binding.configuration().configRefs();
        Object issuer = values.get("issuer");
        if (!(issuer instanceof String text) || text.isBlank()) {
            violations.add("configuration.values.issuer: required");
        } else if (!secureIssuer(text)) {
            violations.add("configuration.values.issuer: must be an absolute https URI (http only on localhost)");
        }
        Object audiences = values.get("audiences");
        Optional<List<String>> protectedServices = protectedServices(identityContract);
        if (protectedServices.isEmpty()) {
            violations.add("configuration.values.audiences: the identity contract does not record which services need "
                    + CAPABILITY + "; compile the composition again");
        } else if (!(audiences instanceof Map<?, ?> map) || !stringMap(map, List.of(), true)) {
            violations.add("configuration.values.audiences: required, mapping each protected service's claim URI to its audience");
        } else {
            Set<String> keys = new java.util.TreeSet<>();
            map.keySet().forEach(key -> keys.add((String) key));
            coverageProblems(keys, protectedServices.get())
                    .forEach(problem -> violations.add("configuration.values.audiences: " + problem));
        }
        if (!stringMap(values.get("claims"), CLAIMS, false)) {
            violations.add("configuration.values.claims: required, naming the tenant, roles and permissions claims");
        }
        for (String ref : REFS) {
            ConfigRef value = refs.get(ref);
            if (value == null || value.uri() == null || value.uri().isBlank()) {
                violations.add("configuration.config_refs." + ref + ": required");
            }
        }
        var provider = binding.providerInstance();
        if (provider == null || provider.credentialsRef() == null || provider.credentialsRef().uri() == null
                || provider.credentialsRef().uri().isBlank()) {
            violations.add("provider_instance.credentials_ref: required, the host's client credentials at the identity provider");
        }
        return List.copyOf(violations);
    }

    /**
     * Shape check: a map of non-blank string keys to non-blank string values containing every required key; empty only when
     * {@code emptyAllowed} (audiences, when no selected component needs identity).
     */
    private static boolean stringMap(Object value, List<String> required, boolean emptyAllowed) {
        if (!(value instanceof Map<?, ?> map) || (map.isEmpty() && !emptyAllowed)) return false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()
                    || !(entry.getValue() instanceof String text) || text.isBlank()) return false;
        }
        return map.keySet().containsAll(required);
    }
}
