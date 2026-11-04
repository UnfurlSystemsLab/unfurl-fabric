package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalAlignmentTest {
    private final CandidateValidator validator = new CandidateValidator();

    @Test
    void refusalIsInformationalWhenNeedAndSelectedDependenciesDoNotRequireIt() {
        CatalogEntry entry = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), null,
                List.of(FabricTestFixtures.refusal("identity-management")));
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));

        CandidateValidity validity = validator.validate(List.of(entry), need);

        assertThat(validity.isValid()).isTrue();
    }

    @Test
    void needRefusalExpectationRequiresSelectedOwner() {
        CatalogEntry storage = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), null,
                List.of(FabricTestFixtures.refusal("identity-management")));
        CatalogEntry identity = FabricTestFixtures.entry("identity-idp", "identity-management");
        Need need = new Need(
                List.of(CapabilityRequirement.requiredOf("storage.put", "^1")),
                List.of(),
                List.of(),
                Set.of("identity-management"),
                null,
                Map.of());

        assertThat(validator.validate(List.of(storage), need).isValid()).isFalse();
        assertThat(validator.validate(List.of(storage, identity), need).isValid()).isTrue();
    }

    @Test
    void peerDependencyMentioningRefusedConcernMakesItRequired() {
        CatalogEntry storage = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), null,
                List.of(FabricTestFixtures.refusal("identity-management")));
        CatalogEntry peer = FabricTestFixtures.entry(
                "audit", "audit.write", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("identity-management@1")), List.of());
        Need need = new Need(
                List.of(CapabilityRequirement.requiredOf("storage.put", "^1"),
                        CapabilityRequirement.requiredOf("audit.write", "^1")),
                List.of(),
                List.of(),
                Set.of(),
                null,
                Map.of());

        CandidateValidity validity = validator.validate(List.of(storage, peer), need);

        assertThat(validity.isValid()).isFalse();
        assertThat(validity.conflicts()).anyMatch(Conflict.RefusalConflict.class::isInstance);
    }

    /** An optional peer dependency on a refused concern doesn't make it required: the peer runs without it. */
    @Test
    void optionalPeerDependencyDoesNotMakeRefusedConcernRequired() {
        CatalogEntry storage = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), null,
                List.of(FabricTestFixtures.refusal("identity-management")));
        CatalogEntry peer = FabricTestFixtures.entry(
                "audit", "audit.write", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of(), List.of("identity-management@1")), List.of());
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"),
                CapabilityRequirement.requiredOf("audit.write", "^1"));

        assertThat(validator.validate(List.of(storage, peer), need).isValid()).isTrue();
    }

    /**
     * Regression: Foundry refuses {@code identity}, and the identity provider component depends on the externally owned
     * {@code oidc.identity-provider}. A dependency whose capability merely contains the refused concern, or one owned by a
     * customer system, must not make the concern required, or Foundry + identity provider could never compose.
     */
    @Test
    void onlyAnExactPeerCapabilityMakesARefusedConcernRequired() {
        CatalogEntry foundry = FabricTestFixtures.entry(
                "foundry", "agent.run", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), null,
                List.of(FabricTestFixtures.refusal("identity")));
        CatalogEntry identityProvider = FabricTestFixtures.entry(
                "keycloak-adapter", "identity-context", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.MANAGED_ADAPTER),
                new Dependencies(List.of("oidc.identity-provider@v1?transport=remote-http&owner=customer-idp",
                        "identity-audit@v1")), List.of());
        CatalogEntry audit = FabricTestFixtures.entry("identity-audit", "identity-audit");
        CatalogEntry customerOwned = FabricTestFixtures.entry(
                "consumer", "consumer.run", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("identity@v1?owner=customer-idp")), List.of());
        Need need = new Need(
                List.of(CapabilityRequirement.requiredOf("agent.run", "^1"),
                        CapabilityRequirement.requiredOf("identity-context", "^1"),
                        CapabilityRequirement.requiredOf("consumer.run", "^1")),
                List.of(),
                List.of(),
                Set.of(),
                null,
                Map.of());

        CandidateValidity validity = validator.validate(List.of(foundry, identityProvider, audit, customerOwned), need);

        assertThat(validity.isValid()).as(validity.conflicts().toString()).isTrue();
    }
}
