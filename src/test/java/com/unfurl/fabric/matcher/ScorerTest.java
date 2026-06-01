package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Stability;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.LifecycleStatus;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {
    private final Scorer scorer = new Scorer();

    @Test
    void scoringIsDeterministicForSameInputs() {
        CatalogEntry entry = FabricTestFixtures.entry("storage-s3", "storage.put");
        Need need = new Need(
                List.of(CapabilityRequirement.requiredOf("storage.put", "^1")),
                List.of(CapabilityRequirement.optionalOf("storage.put", "^1")),
                List.of(),
                Set.of(),
                null,
                Map.of());

        CandidateScore first = scorer.score(List.of(entry), need);
        CandidateScore second = scorer.score(List.of(entry), need);

        assertThat(first).isEqualTo(second);
        assertThat(first.finalScore()).isEqualTo(
                first.optionalCapabilityScore()
                        + first.versionPreferenceScore()
                        + first.dependencyResolutionScore()
                        + first.trustScore()
                        + first.stabilityScore()
                        + first.lifecycleScore()
                        + first.footprintPenalty()
                        + first.riskFlagPenalty());
    }

    @Test
    void deprecatedEntriesScoreLowerButAreNotInvalidatedByScorer() {
        CatalogEntry active = FabricTestFixtures.entry("active", "storage.put");
        CatalogEntry deprecated = FabricTestFixtures.entry(
                "deprecated", "storage.put", "1.0.0", "com.unfurl", "Unfurl",
                Stability.DEPRECATED, LifecycleStatus.DEPRECATED,
                Set.of(BindingMode.IN_PROCESS), null, List.of());
        Need need = Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));

        assertThat(scorer.score(List.of(deprecated), need).finalScore())
                .isLessThan(scorer.score(List.of(active), need).finalScore());
    }
}
