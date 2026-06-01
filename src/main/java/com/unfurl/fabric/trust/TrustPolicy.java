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

    public enum Stability { STABLE, EVOLVING, EXPERIMENTAL }

    public record TrustKey(Path path, String fingerprint) {
        public TrustKey {
            if (path == null) {
                throw new IllegalArgumentException("trust key path is required");
            }
        }
    }
}
