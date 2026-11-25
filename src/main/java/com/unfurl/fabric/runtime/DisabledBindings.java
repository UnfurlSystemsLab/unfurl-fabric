package com.unfurl.fabric.runtime;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimePolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Value Object: the bindings an operator switches off for one deployment (HLD-api-documentation-artifacts §4.4), from
 * {@code --disable-binding <provider-claim-uri>#<capability>} or Studio's {@code disableBindings}.
 *
 * <p>Each {@link Key} disables every child binding whose contract has that provider and provider capability. The signed
 * contracts never change; only the binding's {@code runtime_policy.enabled} does ({@link #apply}). The generator calls
 * {@link #problems} before building, so a malformed, unknown or identity key refuses the whole run and nothing is
 * written (Guard).
 */
public record DisabledBindings(Set<Key> keys) {
    /** The refusal code for every problem with a disable key. */
    public static final String INVALID = "BINDING_DISABLE_INVALID";

    /** One disable key: a provider claim URI and the capability it provides. */
    public record Key(URI provider, String capability) {
        @Override
        public String toString() {
            return provider + "#" + capability;
        }
    }

    /** Defensive copy, keeping the operator's order for messages; never null. */
    public DisabledBindings {
        keys = keys == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    /** No binding disabled: the default of every run. */
    public static DisabledBindings none() {
        return new DisabledBindings(Set.of());
    }

    /**
     * Parser: each entry is {@code <provider-claim-uri>#<capability>}. Claim URIs never contain {@code #}, so the split is
     * at the last {@code #}. Blank entries are ignored, so an empty flag list means none.
     *
     * @throws IllegalArgumentException {@value #INVALID} naming every malformed entry
     */
    public static DisabledBindings parse(Collection<String> entries) {
        Set<Key> keys = new LinkedHashSet<>();
        List<String> malformed = new ArrayList<>();
        for (String raw : entries == null ? List.<String>of() : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) continue;
            int separator = entry.lastIndexOf('#');
            String provider = separator <= 0 ? "" : entry.substring(0, separator).trim();
            String capability = separator < 0 ? "" : entry.substring(separator + 1).trim();
            URI uri = provider.isEmpty() ? null : uri(provider);
            if (uri == null || capability.isEmpty()) {
                malformed.add(entry);
                continue;
            }
            keys.add(new Key(uri, capability));
        }
        if (!malformed.isEmpty()) {
            throw new IllegalArgumentException(INVALID + ": --disable-binding entries must be <provider-claim-uri>#<capability>: "
                    + String.join(", ", malformed));
        }
        return new DisabledBindings(keys);
    }

    /** Whether a child contract's binding is disabled by one of the keys. */
    public boolean matches(CompositionContract contract) {
        return keys.contains(keyOf(contract));
    }

    /**
     * Guard: every key must name a child contract of this composition, and none may be {@code identity-context}, which is
     * used fully while it is in the contract (root CLAUDE.md rule 6). Every problem is returned, in key order.
     */
    public List<String> problems(List<CompositionContract> children) {
        Set<Key> present = new LinkedHashSet<>();
        children.forEach(child -> present.add(keyOf(child)));
        List<String> problems = new ArrayList<>();
        for (Key key : keys) {
            if (IdentityBindingRule.CAPABILITY.equals(key.capability())) {
                problems.add(INVALID + ": " + key + " cannot be disabled: identity is used fully while it is in the "
                        + "contract; to deploy without identity, compile without " + IdentityBindingRule.CAPABILITY + " in the needs");
            } else if (!present.contains(key)) {
                problems.add(INVALID + ": " + key + " matches no child contract of this composition");
            }
        }
        return problems;
    }

    /** The binding as written for this deployment: disabled when a key matches its contract, otherwise unchanged. */
    public RuntimeBinding apply(CompositionContract contract, RuntimeBinding binding) {
        if (!matches(contract)) return binding;
        RuntimePolicy policy = binding.runtimePolicy();
        return new RuntimeBinding(binding.bindingId(), binding.contractId(), binding.contractVersion(), binding.targetEnvironment(),
                binding.providerInstance(), binding.consumerInstance(),
                new RuntimePolicy(false, policy.timeoutMs(), policy.telemetryNamespace(), policy.auditEnabled()),
                binding.configuration(), binding.deploymentControls(), binding.lifecycle(), binding.metadata());
    }

    /** A contract's key: its provider claim URI and provider capability. */
    private static Key keyOf(CompositionContract contract) {
        return new Key(contract.parties().provider().claimUri(), contract.binding().providerCapability());
    }

    /** A URI, or null when the text is not one. */
    private static URI uri(String text) {
        try {
            URI uri = new URI(text);
            return uri.getScheme() == null ? null : uri;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }
}
