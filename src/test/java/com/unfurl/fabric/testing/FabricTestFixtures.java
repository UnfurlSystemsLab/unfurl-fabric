package com.unfurl.fabric.testing;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ComponentKind;
import com.unfurl.dcp.claim.Concern;
import com.unfurl.dcp.claim.ConsumerAccess;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.DomainAssertion;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.Refusal;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.fabric.artifact.ArtifactDescriptor;
import com.unfurl.fabric.catalog.BindingDescriptor;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogMetadata;
import com.unfurl.fabric.catalog.ClaimDescriptor;
import com.unfurl.fabric.catalog.Lifecycle;
import com.unfurl.fabric.catalog.LifecycleStatus;
import com.unfurl.substrate.api.BindingMode;

import java.net.URI;
import java.util.List;
import java.util.Set;

public final class FabricTestFixtures {
    public static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    public static final String CLAIM_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    public static Catalog catalog(CatalogEntry... entries) {
        return new Catalog(List.of(entries));
    }

    public static CatalogEntry entry(String artifactName, String capability) {
        return entry(artifactName, capability, "1.0.0", "com.unfurl", "Unfurl",
                Stability.STABLE, LifecycleStatus.ACTIVE, Set.of(BindingMode.IN_PROCESS),
                null, List.of());
    }

    public static CatalogEntry entry(
            String artifactName,
            String capability,
            String capabilityVersion,
            String group,
            String publisher,
            Stability stability,
            LifecycleStatus lifecycleStatus,
            Set<BindingMode> supportedModes,
            Dependencies dependencies,
            List<Refusal> refusals) {
        Claim claim = claim(artifactName, capability, capabilityVersion, publisher, stability, dependencies, refusals);
        BindingMode defaultMode = supportedModes == null || supportedModes.isEmpty()
                ? BindingMode.IN_PROCESS
                : supportedModes.iterator().next();
        return new CatalogEntry(
                new ArtifactDescriptor(group + ":" + artifactName + ":1.0.0", "jar", "test", SHA, null),
                new ClaimDescriptor(claim, CLAIM_HASH),
                new CatalogMetadata(
                        new Lifecycle(lifecycleStatus, null, null, null),
                        new BindingDescriptor(defaultMode, supportedModes, null, null)),
                null);
    }

    public static CatalogEntry signedEntry(String artifactName, String capability) {
        Claim claim = claim(artifactName, capability, "1.0.0", "Unfurl", Stability.STABLE, null, List.of());
        return new CatalogEntry(
                new ArtifactDescriptor("com.unfurl:" + artifactName + ":1.0.0", "jar", "test", SHA, "sig"),
                new ClaimDescriptor(claim, CLAIM_HASH),
                new CatalogMetadata(Lifecycle.active(), BindingDescriptor.inProcessOnly()),
                null);
    }

    public static Refusal refusal(String concern) {
        return new Refusal(concern, "not owned here", "other-component");
    }

    private static Claim claim(
            String artifactName,
            String capability,
            String capabilityVersion,
            String publisher,
            Stability stability,
            Dependencies dependencies,
            List<Refusal> refusals) {
        return new Claim(
                new Identity(URI.create("urn:unfurl:test:" + artifactName), artifactName, ComponentKind.COMPONENT,
                        "1.0.0", publisher, null),
                new DomainAssertion("test claim for " + artifactName,
                        List.of(new Concern(capability, "owns " + capability, null, List.of(), List.of())),
                        List.of("test boundary")),
                refusals == null ? List.of() : refusals,
                dependencies,
                List.of(new Offer(capability, "offers " + capability, ConsumerAccess.ANY,
                        null, stability, capabilityVersion, false, null)),
                null,
                null,
                null,
                com.unfurl.dcp.fault.FaultPolicy.empty(),
                null);
    }

    private FabricTestFixtures() {
    }
}
