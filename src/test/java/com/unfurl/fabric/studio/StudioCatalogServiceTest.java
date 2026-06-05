package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StudioCatalogServiceTest {

    @Test
    void listsCatalogVisualsPerTenant() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogVisualsResponse first = service.listCatalogVisuals("tenant-a");
        StudioCatalogVisualsResponse second = service.listCatalogVisuals("tenant-b");

        assertThat(first.entries()).hasSize(1);
        assertThat(second.entries()).hasSize(1);
        assertThat(first.catalogHash()).startsWith("sha256:");
        assertThat(second.catalogHash()).startsWith("sha256:");
    }

    @Test
    void verifiesAndAdmitsComponentClaims() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("payment.jar", ""))));

        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("VERIFIED");
                    assertThat(result.catalogEntryId()).isEqualTo("uploaded:payment.jar");
                    assertThat(result.claimHash()).startsWith("sha256:");
                });
        assertThat(response.catalog().entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains("uploaded:payment.jar");
    }

    @Test
    void extractsStarterNeedsForTargetApplication() {
        StudioCatalogService service = new StudioCatalogService();

        StudioNeedsExtractionResponse response = service.extractNeeds(
                "tenant-a",
                "assembly-checkout",
                new StudioNeedsExtractionRequest("Checkout Platform", List.of("workflow.yaml"), "kubernetes-prod"));

        assertThat(response.needsId()).isEqualTo("assembly-checkout-extracted-needs");
        assertThat(response.suggestedNeedsYaml()).contains("checkout.platform.run");
        assertThat(response.defaultDeploymentTarget()).isEqualTo("kubernetes-prod");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void createsListsAndSavesTenantAssemblies() {
        StudioCatalogService service = new StudioCatalogService();

        StudioAssemblySummary created = service.createAssembly("tenant-a", new StudioCreateAssemblyRequest(
                "assembly-checkout",
                "Checkout Platform",
                "kubernetes-prod"));
        StudioSaveDraftResponse saved = service.saveDraft("tenant-a", "assembly-checkout", new StudioSaveDraftRequest(
                "Checkout Platform",
                "assembly-checkout-extracted-needs",
                "kubernetes-prod",
                "CONTAINERIZED_SERVICE",
                "cand-abc123",
                8));

        assertThat(created.assemblyId()).isEqualTo("assembly-checkout");
        assertThat(saved.status()).isEqualTo("SAVED");
        assertThat(saved.assembly().needsId()).isEqualTo("assembly-checkout-extracted-needs");
        assertThat(service.listAssemblies("tenant-a").assemblies())
                .extracting(StudioAssemblySummary::assemblyId)
                .contains("assembly-demo", "assembly-checkout");
    }
}
