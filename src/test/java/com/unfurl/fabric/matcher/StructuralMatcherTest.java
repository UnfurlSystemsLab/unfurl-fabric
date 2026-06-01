package com.unfurl.fabric.matcher;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.fabric.trust.RejectedEntry;
import com.unfurl.fabric.trust.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThat(noMatch.potentiallyRelevantRejections()).containsExactly(rejection);
    }
}
