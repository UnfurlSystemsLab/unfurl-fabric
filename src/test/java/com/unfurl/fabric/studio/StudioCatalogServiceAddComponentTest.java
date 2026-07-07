package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the catalog-membership gate inside
 * {@link StudioCatalogService#applyIntent}: ADD_COMPONENT and
 * REPLACE_COMPONENT intents must reference a catalogEntryId present in
 * the tenant's catalog.
 */
class StudioCatalogServiceAddComponentTest {

    @Test
    void addComponentAcceptsKnownCatalogEntry() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "cand-initial", "alice", "Alice"));

        StudioIntentRequest intent = baseAddIntent(created);
        intent.put("catalogEntryId", "com.unfurl:validation-service:1.1.0");

        StudioIntentResponse response = service.applyIntent(
                "tenant-a", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.updatedCandidateId()).isEqualTo("com.unfurl:validation-service:1.1.0");
    }

    @Test
    void addComponentRejectsUnknownCatalogEntry() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "cand-initial", "alice", "Alice"));

        StudioIntentRequest intent = baseAddIntent(created);
        intent.put("catalogEntryId", "com.unfurl:not-registered:9.9.9");

        StudioIntentResponse response = service.applyIntent(
                "tenant-a", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reason()).isEqualTo("CATALOG_ENTRY_NOT_FOUND");
        assertThat(response.details())
                .contains("com.unfurl:not-registered:9.9.9")
                .contains("tenant-a");
    }

    @Test
    void addComponentRejectsBlankCatalogEntry() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "cand-initial", "alice", "Alice"));

        StudioIntentRequest intent = baseAddIntent(created);
        intent.put("catalogEntryId", "");

        StudioIntentResponse response = service.applyIntent(
                "tenant-a", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reason()).isEqualTo("CATALOG_ENTRY_REQUIRED");
    }

    @Test
    void replaceComponentRejectsUnknownNewCatalogEntry() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "cand-initial", "alice", "Alice"));

        StudioIntentRequest intent = baseAddIntent(created);
        intent.type = "REPLACE_COMPONENT";
        intent.put("newCatalogEntryId", "com.unfurl:not-registered:9.9.9");

        StudioIntentResponse response = service.applyIntent(
                "tenant-a", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reason()).isEqualTo("CATALOG_ENTRY_NOT_FOUND");
    }

    @Test
    void otherIntentTypesAreNotGatedAgainstCatalog() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "cand-initial", "alice", "Alice"));

        StudioIntentRequest intent = baseAddIntent(created);
        intent.type = "CONNECT";
        // Other intent types never reference catalog entry ids; payload
        // is opaque from the gate's perspective.
        intent.put("fromPort", "p1");
        intent.put("toPort", "p2");

        StudioIntentResponse response = service.applyIntent(
                "tenant-a", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("VALID");
    }

    /**
     * Regression test: REMOVE_COMPONENT clears the current candidate pointer when the active
     * catalog entry is removed so later compile/export requests cannot target stale draft state.
     */
    @Test
    void removeComponentClearsCurrentCandidateWhenItTargetsActiveCatalogEntry() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "com.unfurl:validation-service:1.1.0", "alice", "Alice"));

        StudioIntentRequest intent = baseAddIntent(created);
        intent.type = "REMOVE_COMPONENT";
        intent.put("componentId", "component.validation-service");
        intent.put("catalogEntryId", "com.unfurl:validation-service:1.1.0");

        StudioIntentResponse response = service.applyIntent(
                "tenant-a", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.updatedCandidateId()).isEmpty();
        assertThat(response.session().currentCandidateId()).isEmpty();
    }

    private static StudioIntentRequest baseAddIntent(StudioCreateDraftCompositionResponse created) {
        StudioIntentRequest intent = new StudioIntentRequest();
        intent.tenantId = "tenant-a";
        intent.assemblyId = "assembly-demo";
        intent.sessionId = created.session().sessionId();
        intent.baseRevision = 0;
        intent.type = "ADD_COMPONENT";
        intent.collaboratorId = "alice";
        return intent;
    }
}
