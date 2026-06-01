package com.unfurl.fabric.compiler;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.PlanningWarning;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

final class CompilerFixtures {
    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final HostOwnerMeta HOST = new HostOwnerMeta(
            URI.create("urn:unfurl:host:test"),
            "1.0.0",
            "fabric-test");

    static CatalogEntry storageEntry() {
        return FabricTestFixtures.entry("storage-s3", "storage.put");
    }

    static Need storageNeed() {
        return Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1"));
    }

    static CompositionCandidate candidate(CatalogEntry... entries) {
        List<CatalogEntry> entryList = List.of(entries);
        return new CompositionCandidate(
                CompositionCandidate.computeId(entryList),
                entryList,
                Set.of("storage.put"),
                Set.of(),
                List.of(),
                List.of(new PlanningWarning.OptionalCapabilityMissing("audit.write")),
                CandidateScore.of(0, 5, 10, 5, 30, 30, -3, 0));
    }

    static CompiledContract compiled() {
        return new ContractCompiler(FIXED_CLOCK).compile(candidate(storageEntry()), storageNeed(), HOST);
    }

    private CompilerFixtures() {
    }
}
