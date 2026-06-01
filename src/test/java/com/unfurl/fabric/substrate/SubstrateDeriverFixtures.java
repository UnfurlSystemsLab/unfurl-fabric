package com.unfurl.fabric.substrate;

import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.LifecycleStatus;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.substrate.api.BindingMode;

import java.util.List;
import java.util.Set;

final class SubstrateDeriverFixtures {
    static CatalogEntry entry(String name, String... dependencies) {
        return FabricTestFixtures.entry(
                name,
                "capability." + name,
                "1.0.0",
                "com.unfurl",
                "Unfurl",
                Stability.STABLE,
                LifecycleStatus.ACTIVE,
                Set.of(BindingMode.IN_PROCESS),
                new Dependencies(List.of(dependencies)),
                List.of());
    }

    static CompositionCandidate candidate(CatalogEntry... entries) {
        List<CatalogEntry> selected = List.of(entries);
        return new CompositionCandidate(
                CompositionCandidate.computeId(selected),
                selected,
                Set.of(),
                Set.of(),
                List.of(),
                List.of(),
                CandidateScore.of(0, 0, 10, 5, 30, 30, -3, 0));
    }

    private SubstrateDeriverFixtures() {
    }
}
