package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.ArtifactConstraint;
import com.unfurl.fabric.needs.ArtifactVersionRange;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateValidatorTest {
    private final CandidateValidator validator = new CandidateValidator();

    @Test
    void validCandidatePassesAllGates() {
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));

        CandidateValidity validity = validator.validate(List.of(entry), need);

        assertThat(validity.isValid()).isTrue();
        assertThat(validity.conflicts()).isEmpty();
    }

    @Test
    void requiredCapabilitiesAndArtifactConstraintsAreValidityGates() {
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");
        Need need = new Need(
                List.of(CapabilityRequirement.requiredOf("storage.put", "^2")),
                List.of(),
                List.of(new ArtifactConstraint("com.unfurl", "storage-s3", new ArtifactVersionRange("^2"))),
                Set.of(),
                null,
                Map.of());

        CandidateValidity validity = validator.validate(List.of(entry), need);

        assertThat(validity.isValid()).isFalse();
        assertThat(validity.conflicts()).anyMatch(Conflict.VersionConflict.class::isInstance);
        assertThat(validity.conflicts()).anyMatch(Conflict.ArtifactConflict.class::isInstance);
    }

    @Test
    void requiredOfferDetailsAreValidityGate() {
        CatalogEntry simpleAgent = FabricTestFixtures.entryWithOfferDetails(
                "foundry-simple",
                "agent.run",
                Map.of("execution_modes", List.of("simple")));
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf(
                "agent.run",
                "^1",
                Map.of("execution_modes", List.of("harness"))));

        CandidateValidity validity = validator.validate(List.of(simpleAgent), need);

        assertThat(validity.isValid()).isFalse();
        assertThat(validity.conflicts()).anyMatch(Conflict.OfferDetailConflict.class::isInstance);
    }

    @Test
    void bindingPreferenceIsGateNotScoreDimension() {
        CatalogEntry entry = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), null, List.of());
        Need need = new Need(
                List.of(CapabilityRequirement.requiredOf("storage.put", "^1")),
                List.of(),
                List.of(),
                Set.of(),
                null,
                Map.of("storage.put", BindingMode.REMOTE_HTTP));

        CandidateValidity validity = validator.validate(List.of(entry), need);

        assertThat(validity.isValid()).isFalse();
        assertThat(validity.bindingsCompatible()).isFalse();
        assertThat(validity.conflicts()).anyMatch(Conflict.BindingConflict.class::isInstance);
    }

    @Test
    void unresolvedRequiredDependencyIsGateButExternallyOwnedDependenciesAreExternal() {
        CatalogEntry unresolved = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS), new Dependencies(List.of("identity.validate@1")), List.of());
        CatalogEntry customerControlled = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("identity.validate@1?owner=customer-controlled")), List.of());
        CatalogEntry hostOwned = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("spring-ai.chat-client@1?owner=host")), List.of());
        CatalogEntry fabricOwned = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("fabric.authoring.delegate@1?owner=fabric")), List.of());
        CatalogEntry customerIdpOwned = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("identity-context@v1?owner=customer-idp")), List.of());
        CatalogEntry customerAuditOwned = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("audit-sink@v1?owner=customer-audit-store")), List.of());
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));

        assertThat(validator.validate(List.of(unresolved), need).isValid()).isFalse();
        assertThat(validator.validate(List.of(customerControlled), need).isValid()).isTrue();
        assertThat(validator.validate(List.of(hostOwned), need).isValid()).isTrue();
        assertThat(validator.validate(List.of(fabricOwned), need).isValid()).isTrue();
        assertThat(validator.validate(List.of(customerIdpOwned), need).isValid()).isTrue();
        assertThat(validator.validate(List.of(customerAuditOwned), need).isValid()).isTrue();
    }

    @Test
    void substrateDependenciesAreDerivedNotComponentGates() {
        CatalogEntry entry = FabricTestFixtures.entry(
                "storage-s3", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of("object-store@^1?substrate=true")),
                List.of());
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));

        CandidateValidity validity = validator.validate(List.of(entry), need);

        assertThat(validity.isValid()).isTrue();
        assertThat(validity.conflicts()).isEmpty();
    }
}
