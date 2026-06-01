package com.unfurl.fabric.matcher;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.testing.FabricTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link CompositionCandidate#computeId(List)} produces deterministic,
 * content-pinned identifiers. The ID is the anchor for {@code fabric compile --select <id>};
 * any sloppiness here breaks the Ambiguous-compile gate.
 */
class CandidateIdTest {

    @Test
    void sameEntriesProduceSameCandidateId() {
        CatalogEntry a = FabricTestFixtures.entry("storage-s3", "storage.put");
        CatalogEntry b = FabricTestFixtures.entry("audit-writer", "audit.write");

        String first = CompositionCandidate.computeId(List.of(a, b));
        String second = CompositionCandidate.computeId(List.of(a, b));

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("cand-");
        assertThat(first).hasSize("cand-".length() + 12);
    }

    @Test
    void candidateIdIsIndependentOfEntryListOrder() {
        CatalogEntry a = FabricTestFixtures.entry("storage-s3", "storage.put");
        CatalogEntry b = FabricTestFixtures.entry("audit-writer", "audit.write");

        String ordered = CompositionCandidate.computeId(List.of(a, b));
        String reversed = CompositionCandidate.computeId(List.of(b, a));

        assertThat(ordered).isEqualTo(reversed);
    }

    @Test
    void differentEntriesProduceDifferentCandidateIds() {
        CatalogEntry a = FabricTestFixtures.entry("storage-s3", "storage.put");
        CatalogEntry b = FabricTestFixtures.entry("storage-postgres", "storage.put");

        String firstId = CompositionCandidate.computeId(List.of(a));
        String secondId = CompositionCandidate.computeId(List.of(b));

        assertThat(firstId).isNotEqualTo(secondId);
    }

    @Test
    void emptyEntriesProducesSentinelId() {
        assertThat(CompositionCandidate.computeId(List.of())).isEqualTo("cand-empty");
        assertThat(CompositionCandidate.computeId(null)).isEqualTo("cand-empty");
    }
}
