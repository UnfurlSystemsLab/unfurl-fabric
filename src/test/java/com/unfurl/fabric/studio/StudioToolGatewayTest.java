package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite: verifies the Foundry-compatible Studio tool gateway payloads stay
 * bounded and governed before model loops consume them as prompt context.
 */
class StudioToolGatewayTest {
    /**
     * Regression guard: unfiltered catalog hydration returns a bounded preview,
     * not a full tenant catalog dump, while still reporting the omitted count so
     * the authoring agent can ask for filtered follow-up hydration.
     */
    @Test
    void catalogQueryBoundsUnfilteredEntryPreview() {
        StudioCatalogService service = new StudioCatalogService();
        List<StudioVisualCatalogEntry> entries = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            entries.add(visualEntry("uploaded:catalog-entry-" + index + "-0.1.0-SNAPSHOT.jar"));
        }
        service.loadCatalogSnapshot("tenant-local", new StudioCatalogSnapshot(
                "tenant-local",
                "sha256:large-catalog",
                entries,
                List.of()));
        StudioToolGateway gateway = new StudioToolGateway(service, StudioJson.mapper());

        StudioToolCallResult result = gateway.execute(new StudioToolCallRequest(
                "catalog-query",
                "fabric.catalog-query",
                Map.of("tenantId", "tenant-local"),
                Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .containsEntry("status", "PASS")
                .containsEntry("entryTotal", 20)
                .containsEntry("entryLimit", 12)
                .containsEntry("entriesOmitted", 8);
        assertThat(asList(result.output().get("entries"))).hasSize(12);
    }

    /**
     * Test data factory: creates a minimal visual catalog entry with stable
     * content hashes and no ports for gateway projection tests.
     */
    private static StudioVisualCatalogEntry visualEntry(String catalogEntryId) {
        return new StudioVisualCatalogEntry(
                catalogEntryId,
                "sha256:claim-" + catalogEntryId.hashCode(),
                "sha256:artifact-" + catalogEntryId.hashCode(),
                Map.of("ports", List.of()),
                Map.of(),
                List.of());
    }

    /**
     * Assertion helper: casts JSON-like arrays from tool outputs for AssertJ.
     */
    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
