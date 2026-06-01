package com.unfurl.fabric.advisor;

import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.matcher.UnmetCapabilityRequirement;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;

import java.util.List;
import java.util.Set;

final class AdvisorFixtures {
    static AdvisorContext exactContext() {
        CatalogEntry entry = FabricTestFixtures.entry("storage", "storage.put");
        CompositionCandidate candidate = candidate(entry);
        return new AdvisorContext(FabricTestFixtures.catalog(entry),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1")),
                new MatchResult.ExactMatch(candidate));
    }

    static AdvisorContext ambiguousContext() {
        CatalogEntry first = FabricTestFixtures.entry("storage-a", "storage.put");
        CatalogEntry second = FabricTestFixtures.entry("storage-b", "storage.put");
        return new AdvisorContext(FabricTestFixtures.catalog(first, second),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1")),
                new MatchResult.Ambiguous(List.of(candidate(first), candidate(second))));
    }

    static AdvisorContext noMatchContext() {
        CatalogEntry rejected = FabricTestFixtures.entry("storage", "storage.put");
        return new AdvisorContext(FabricTestFixtures.catalog(rejected),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("vector.search", "^1")),
                new MatchResult.NoMatch(
                        List.of(new UnmetCapabilityRequirement("vector.search", "^1", null)),
                        List.of(),
                        List.of()));
    }

    private static CompositionCandidate candidate(CatalogEntry entry) {
        return new CompositionCandidate(
                CompositionCandidate.computeId(List.of(entry)),
                List.of(entry),
                Set.of(entry.claimDescriptor().claim().offers().getFirst().capability()),
                Set.of(),
                List.of(),
                List.of(),
                CandidateScore.of(0, 5, 0, 5, 10, 10, 0, 0));
    }

    private AdvisorFixtures() {
    }
}
