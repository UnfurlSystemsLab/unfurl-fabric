package com.unfurl.fabric.trust;

import com.unfurl.dcp.claim.Stability;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.LifecycleStatus;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrustClassifierTest {
    private final TrustClassifier classifier = new TrustClassifier();

    @Test
    void permissivePolicyAllowsActiveEntries() {
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");

        TrustClassification classification = classifier.classify(
                FabricTestFixtures.catalog(entry), TrustPolicy.permissive());

        assertThat(classification.allowedEntries()).containsExactly(entry);
        assertThat(classification.rejectedEntries()).isEmpty();
    }

    @Test
    void surfacesAllSevenRejectionReasonVariants() {
        CatalogEntry entry = FabricTestFixtures.entry(
                "storage-s3",
                "storage.put",
                "1.0.0",
                "com.blocked",
                "UntrustedCo",
                Stability.EXPERIMENTAL,
                LifecycleStatus.DEPRECATED,
                Set.of(BindingMode.IN_PROCESS),
                null,
                List.of());
        TrustPolicy policy = new TrustPolicy(
                Set.of("TrustedCo"),
                Set.of("com.unfurl"),
                TrustPolicy.Stability.STABLE,
                false,
                true,
                List.of("identity.*"),
                List.of("storage.*"),
                Set.of(LifecycleStatus.ACTIVE),
                List.of());

        TrustClassification classification = classifier.classify(FabricTestFixtures.catalog(entry), policy);

        assertThat(classification.allowedEntries()).isEmpty();
        assertThat(classification.rejectedEntries()).hasSize(1);
        assertThat(classification.rejectedEntries().get(0).reasons())
                .extracting(Object::getClass)
                .contains(
                        RejectionReason.VendorNotTrusted.class,
                        RejectionReason.ArtifactGroupNotAllowed.class,
                        RejectionReason.LifecycleNotAllowed.class,
                        RejectionReason.UnsignedClaim.class,
                        RejectionReason.CapabilityDenied.class,
                        RejectionReason.CapabilityNotAllowed.class,
                        RejectionReason.StabilityBelowFloor.class);
    }

    @Test
    void signedTrustedStableEntryIsAllowed() {
        CatalogEntry entry = FabricTestFixtures.signedEntry("storage-s3", "storage.put");
        TrustPolicy policy = new TrustPolicy(
                Set.of("Unfurl"),
                Set.of("com.unfurl"),
                TrustPolicy.Stability.STABLE,
                false,
                true,
                List.of("storage.*"),
                List.of(),
                Set.of(LifecycleStatus.ACTIVE),
                List.of());

        TrustClassification classification = classifier.classify(FabricTestFixtures.catalog(entry), policy);

        assertThat(classification.allowedEntries()).containsExactly(entry);
        assertThat(classification.rejectedEntries()).isEmpty();
    }
}
