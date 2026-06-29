package com.unfurl.fabric.trust;

import com.unfurl.fabric.catalog.LifecycleStatus;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Operator-supplied environmental governance policy. Independent from DCP claim validity:
 * DCP says "can this component satisfy the contract?", trust policy says "is this component
 * allowed in this environment?".
 *
 * <p>Pattern: immutable <b>policy/configuration value object</b> with a normalizing compact constructor
 * and a {@code permissive()} factory for the default-open policy.
 *
 * @param trustedVendors            allowed publishers (empty = any vendor allowed).
 * @param allowedArtifactGroups     allowed artifact groups (empty = any group allowed).
 * @param minimumStability          minimum offer stability floor.
 * @param allowExperimental         whether experimental components are permitted.
 * @param requireSignedClaims       whether unsigned claims are rejected.
 * @param allowedCapabilityPatterns glob patterns a capability must match (empty = all allowed).
 * @param deniedCapabilityPatterns  glob patterns that reject a capability.
 * @param allowedLifecycleStatuses  permitted lifecycle statuses (default ACTIVE only).
 * @param trustKeys                 trusted signing keys referenced by this policy.
 */
public record TrustPolicy(
        Set<String> trustedVendors,
        Set<String> allowedArtifactGroups,
        Stability minimumStability,
        boolean allowExperimental,
        boolean requireSignedClaims,
        List<String> allowedCapabilityPatterns,
        List<String> deniedCapabilityPatterns,
        Set<LifecycleStatus> allowedLifecycleStatuses,
        List<TrustKey> trustKeys
) {
    /**
     * Compact constructor: applies safe defaults and defensive copies for every collection/enum field
     * (e.g. null stability → STABLE, null lifecycle set → {ACTIVE}).
     */
    public TrustPolicy {
        trustedVendors = trustedVendors == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(trustedVendors));
        allowedArtifactGroups = allowedArtifactGroups == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(allowedArtifactGroups));
        minimumStability = minimumStability == null ? Stability.STABLE : minimumStability;
        allowedCapabilityPatterns = allowedCapabilityPatterns == null
                ? List.of() : List.copyOf(allowedCapabilityPatterns);
        deniedCapabilityPatterns = deniedCapabilityPatterns == null
                ? List.of() : List.copyOf(deniedCapabilityPatterns);
        allowedLifecycleStatuses = allowedLifecycleStatuses == null
                ? Set.of(LifecycleStatus.ACTIVE)
                : Set.copyOf(new LinkedHashSet<>(allowedLifecycleStatuses));
        trustKeys = trustKeys == null ? List.of() : List.copyOf(trustKeys);
    }

    /**
     * Permissive default policy: every vendor, every group, every active component, no
     * signature requirement. Useful for the deterministic-core MVP before key infrastructure
     * is in place.
     *
     * @return an all-allowing trust policy.
     */
    public static TrustPolicy permissive() {
        return new TrustPolicy(
                Set.of(),
                Set.of(),
                Stability.EXPERIMENTAL,
                true,
                false,
                List.of("*"),
                List.of(),
                Set.of(LifecycleStatus.EXPERIMENTAL, LifecycleStatus.ACTIVE, LifecycleStatus.DEPRECATED),
                List.of());
    }

    /** Stability floor levels, ascending: EXPERIMENTAL &lt; EVOLVING &lt; STABLE. */
    public enum Stability {
        /** Strictest floor satisfied only by STABLE offers. */
        STABLE,
        /** Mid floor: EVOLVING or better. */
        EVOLVING,
        /** Lowest floor: any non-deprecated offer. */
        EXPERIMENTAL
    }

    /**
     * Reference to a trusted signing key: its PEM path and (optionally) its expected fingerprint.
     *
     * @param path        filesystem path to the key (required).
     * @param fingerprint expected key fingerprint, or null to accept whatever the file contains.
     */
    public record TrustKey(Path path, String fingerprint) {
        /** Compact constructor: requires a non-null key path. */
        public TrustKey {
            if (path == null) {
                throw new IllegalArgumentException("trust key path is required");
            }
        }
    }
}
