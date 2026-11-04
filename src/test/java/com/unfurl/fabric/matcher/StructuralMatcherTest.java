package com.unfurl.fabric.matcher;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.fabric.trust.RejectedEntry;
import com.unfurl.fabric.trust.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralMatcherTest {
    private final StructuralMatcher matcher = new StructuralMatcher();

    @Test
    void returnsExactMatchForSingleValidCandidate() {
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");

        MatchResult result = matcher.match(
                List.of(entry),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1")),
                List.of());

        assertThat(result).isInstanceOf(MatchResult.ExactMatch.class);
        MatchResult.ExactMatch exact = (MatchResult.ExactMatch) result;
        assertThat(exact.candidate().entries()).containsExactly(entry);
    }

    @Test
    void returnsAmbiguousForMultipleValidCandidatesRankedDeterministically() {
        CatalogEntry a = FabricTestFixtures.entry("storage-a", "storage.put");
        CatalogEntry b = FabricTestFixtures.entry("storage-b", "storage.put");

        MatchResult result = matcher.match(
                List.of(b, a),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1")),
                List.of());

        assertThat(result).isInstanceOf(MatchResult.Ambiguous.class);
        MatchResult.Ambiguous ambiguous = (MatchResult.Ambiguous) result;
        assertThat(ambiguous.candidates()).hasSize(2);
        assertThat(ambiguous.candidates().get(0).entries().get(0).artifact().coordinates())
                .isLessThan(ambiguous.candidates().get(1).entries().get(0).artifact().coordinates());
    }

    @Test
    void filtersProvidersByRequiredOfferDetails() {
        CatalogEntry simpleAgent = FabricTestFixtures.entryWithOfferDetails(
                "foundry-simple",
                "agent.run",
                Map.of("execution_modes", List.of("simple")));
        CatalogEntry harnessAgent = FabricTestFixtures.entryWithOfferDetails(
                "foundry-harness",
                "agent.run",
                Map.of("execution_modes", List.of("simple", "harness")));

        MatchResult result = matcher.match(
                List.of(simpleAgent, harnessAgent),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf(
                        "agent.run",
                        "^1",
                        Map.of("execution_modes", List.of("harness")))),
                List.of());

        assertThat(result).isInstanceOf(MatchResult.ExactMatch.class);
        MatchResult.ExactMatch exact = (MatchResult.ExactMatch) result;
        assertThat(exact.candidate().entries()).containsExactly(harnessAgent);
    }

    @Test
    void returnsNoMatchWithRelevantRejectionSurfacing() {
        CatalogEntry rejectedProvider = FabricTestFixtures.entry("storage-s3", "storage.put");
        RejectedEntry rejection = new RejectedEntry(
                rejectedProvider,
                List.of(new RejectionReason.VendorNotTrusted("BlockedCo")));

        MatchResult result = matcher.match(
                List.of(),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1")),
                List.of(rejection));

        assertThat(result).isInstanceOf(MatchResult.NoMatch.class);
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) result;
        assertThat(noMatch.missing()).extracting(UnmetCapabilityRequirement::capability)
                .containsExactly("storage.put");
        assertThat(noMatch.missing().get(0).reason()).isEqualTo(UnmetCapabilityRequirement.Reason.REJECTED_BY_TRUST_POLICY);
        assertThat(noMatch.missing().get(0).detail())
                .contains("the needs require storage.put ^1", rejectedProvider.artifact().coordinates(), "trust policy");
        assertThat(noMatch.potentiallyRelevantRejections()).containsExactly(rejection);
    }

    /** Every capability in the needs is mandatory: one that nothing offers fails the match and names the fix. */
    @Test
    void capabilityNotInTheCatalogIsAnUnmetNeedWithItsFix() {
        CatalogEntry storage = FabricTestFixtures.entry("storage-s3", "storage.put");

        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) matcher.match(
                List.of(storage),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"),
                        CapabilityRequirement.requiredOf("identity-context", "^1")),
                List.of());

        assertThat(noMatch.missing()).singleElement().satisfies(missing -> {
            assertThat(missing.capability()).isEqualTo("identity-context");
            assertThat(missing.reason()).isEqualTo(UnmetCapabilityRequirement.Reason.NOT_IN_CATALOG);
            assertThat(missing.detail()).isEqualTo("the needs require identity-context ^1, but no catalog entry offers "
                    + "identity-context; add a component that offers it to the catalog, or remove it from the needs");
        });
        assertThat(NoMatchExplanation.reasons(noMatch))
                .containsExactly(NoMatchExplanation.UNMET_PREFIX + noMatch.missing().get(0).detail());
    }

    /** A capability offered only at other versions reports the versions that exist and who offers them. */
    @Test
    void capabilityAtAnotherVersionNamesTheOfferedVersions() {
        CatalogEntry storage = FabricTestFixtures.entry("storage-s3", "storage.put");

        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) matcher.match(
                List.of(storage),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^7")),
                List.of());

        UnmetCapabilityRequirement missing = noMatch.missing().get(0);
        assertThat(missing.reason()).isEqualTo(UnmetCapabilityRequirement.Reason.VERSION_NOT_OFFERED);
        assertThat(missing.detail()).contains("the needs require storage.put ^7", "the catalog offers only storage.put 1.0.0 ("
                + storage.artifact().coordinates() + ")", "change the version range in the needs");
    }

    /** A capability at a matching version but without the required offer details says so, rather than "not found". */
    @Test
    void capabilityWithoutTheRequiredDetailsSaysWhichDetails() {
        CatalogEntry simpleAgent = FabricTestFixtures.entryWithOfferDetails(
                "foundry-simple", "agent.run", Map.of("execution_modes", List.of("simple")));

        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) matcher.match(
                List.of(simpleAgent),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf(
                        "agent.run", "^1", Map.of("execution_modes", List.of("harness")))),
                List.of());

        UnmetCapabilityRequirement missing = noMatch.missing().get(0);
        assertThat(missing.reason()).isEqualTo(UnmetCapabilityRequirement.Reason.OFFER_DETAILS_UNSATISFIED);
        assertThat(missing.detail()).contains("with offer details", "harness", "does not offer those details");
    }

    /**
     * A selected component's own claim need that nothing selected offers fails the match with a conflict naming the
     * component, the dependency and the fix (Fabric selects providers only for the needs, so the needs must ask for it).
     * Before, the CLI printed only "no match".
     */
    @Test
    void unmetClaimDependencyNamesTheComponentAndTheFix() {
        CatalogEntry foundry = FabricTestFixtures.entry(
                "foundry", "agent.run", "1.0.0", "com.unfurl", "Unfurl",
                com.unfurl.dcp.claim.Stability.STABLE,
                com.unfurl.fabric.catalog.LifecycleStatus.ACTIVE,
                java.util.Set.of(com.unfurl.substrate.api.BindingMode.IN_PROCESS),
                new com.unfurl.dcp.claim.Dependencies(List.of("workflow.execute@v1?transport=dcp")), List.of());

        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) matcher.match(
                List.of(foundry),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("agent.run", "^1")),
                List.of());

        assertThat(noMatch.missing()).isEmpty();
        assertThat(noMatch.conflicts()).singleElement().isInstanceOfSatisfying(Conflict.DependencyConflict.class, conflict -> {
            assertThat(conflict.component()).isEqualTo(foundry.artifact().coordinates());
            assertThat(conflict.capability()).isEqualTo("workflow.execute");
            assertThat(conflict.detail()).isEqualTo(foundry.artifact().coordinates()
                    + " needs workflow.execute@v1?transport=dcp, but no selected component offers workflow.execute; add "
                    + "workflow.execute to the needs' requiredCapabilities so a provider is selected");
        });
        assertThat(NoMatchExplanation.reasons(noMatch)).singleElement().asString()
                .startsWith(NoMatchExplanation.CONFLICT_PREFIX);
    }
}
