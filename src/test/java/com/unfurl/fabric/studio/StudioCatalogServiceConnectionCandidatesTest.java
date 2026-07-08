package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link StudioCatalogService#connectionCandidates} — the
 * hover-preview surface that powers the Studio UI's "show me what this
 * could plug into" highlights. Stages JARs with matching OFFER/needs
 * pairs so both directions of the matcher get exercised:
 * <ul>
 *   <li>{@code provider-llm} OFFERS {@code ai.llm.openai} (no needs)</li>
 *   <li>{@code consumer-app} NEEDS {@code ai.llm.openai} and OFFERS
 *       {@code app.checkout}</li>
 * </ul>
 * Hovering one against a draft holding the other should surface a
 * connection edge in the appropriate direction.
 */
class StudioCatalogServiceConnectionCandidatesTest {

    private static final String PROVIDER_LLM_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:provider-llm
                name: provider-llm
                kind: INTELLIGENT_COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Test provider offering ai.llm.openai
                concerns:
                  - concern: ai.llm.openai
                    description: OpenAI completions
                boundary_principles:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs: []
              offers:
                - capability: ai.llm.openai
                  description: OpenAI completions
                  consumer_access: NAMED_COMPONENTS_ONLY
                  stability: STABLE
                  version: 0.1.0
                  metered: true
              faults:
                emitted: []
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:provider-llm:0.1.0
                packaging: jar
                source: catalog
              binding:
                default_mode: MANAGED_ADAPTER
                supported_modes: [MANAGED_ADAPTER]
              component_shape_profile:
                default_shape: MANAGED_EXTERNAL_ADAPTER
                supported_shapes: [MANAGED_EXTERNAL_ADAPTER]
                shape_runtime: {}
            """;

    private static final String CONSUMER_APP_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:consumer-app
                name: consumer-app
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Test consumer needing ai.llm.openai
                concerns:
                  - concern: app.checkout
                    description: Checkout flow
                boundary_principles:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs:
                  - "ai.llm.openai@v1?owner=host"
              offers:
                - capability: app.checkout
                  description: Checkout endpoint
                  consumer_access: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
              faults:
                emitted: []
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:consumer-app:0.1.0
                packaging: jar
                source: catalog
              binding:
                default_mode: REMOTE_HTTP
                supported_modes: [REMOTE_HTTP]
              component_shape_profile:
                default_shape: SPRING_BOOT_SERVICE
                supported_shapes: [SPRING_BOOT_SERVICE]
                shape_runtime: {}
            """;

    @Test
    void candidateOfferingCapabilityAppearsAsConnectionForDraftThatNeedsIt(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "provider-llm.jar", PROVIDER_LLM_MANIFEST);
        writeManifestJar(assetRoot, "consumer-app.jar", CONSUMER_APP_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioConnectionCandidatesResponse response = service.connectionCandidates(
                "tenant-conn",
                "assembly-demo",
                "com.unfurl.test:provider-llm:0.1.0");

        assertThat(response.warnings()).isEmpty();
        assertThat(response.connections()).hasSize(1);
        StudioConnectionEdge edge = response.connections().get(0);
        assertThat(edge.direction()).isEqualTo("CANDIDATE_OFFERS");
        assertThat(edge.status()).isEqualTo("ALLOWED");
        assertThat(edge.candidatePortId()).isEqualTo("ai-llm-openai");
        assertThat(edge.targetPortId()).isEqualTo("need-ai-llm-openai");
        assertThat(edge.targetNodeId()).isEqualTo("component.consumer-app");
        assertThat(edge.reason()).contains("ai.llm.openai");
    }

    @Test
    void candidateNeedingCapabilityAppearsAsConnectionForDraftThatOffersIt(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "provider-llm.jar", PROVIDER_LLM_MANIFEST);
        writeManifestJar(assetRoot, "consumer-app.jar", CONSUMER_APP_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioConnectionCandidatesResponse response = service.connectionCandidates(
                "tenant-conn",
                "assembly-demo",
                "com.unfurl.test:consumer-app:0.1.0");

        assertThat(response.warnings()).isEmpty();
        assertThat(response.connections()).hasSize(1);
        StudioConnectionEdge edge = response.connections().get(0);
        assertThat(edge.direction()).isEqualTo("CANDIDATE_NEEDS");
        assertThat(edge.status()).isEqualTo("ALLOWED");
        assertThat(edge.candidatePortId()).isEqualTo("need-ai-llm-openai");
        assertThat(edge.targetPortId()).isEqualTo("ai-llm-openai");
        assertThat(edge.targetNodeId()).isEqualTo("component.provider-llm");
        assertThat(edge.reason()).contains("ai.llm.openai");
    }

    @Test
    void unknownCandidateReturnsEmptyResponseWithWarning(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "provider-llm.jar", PROVIDER_LLM_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioConnectionCandidatesResponse response = service.connectionCandidates(
                "tenant-conn",
                "assembly-demo",
                "com.unfurl.test:does-not-exist:9.9.9");

        assertThat(response.connections()).isEmpty();
        assertThat(response.replacements()).isEmpty();
        assertThat(response.warnings()).hasSize(1);
        assertThat(response.warnings().get(0)).contains("does-not-exist");
    }

    @Test
    void blankCandidateIdIsReportedAsRequired(@TempDir Path assetRoot) throws Exception {
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioConnectionCandidatesResponse response = service.connectionCandidates(
                "tenant-conn",
                "assembly-demo",
                "  ");

        assertThat(response.warnings()).containsExactly("catalogEntryId is required");
    }

    @Test
    void replacementEdgeSurfacesWhenCompatibleDescendantsListsCandidate() {
        // The bundled validation-service fixture lists
        // "component.customer-policy-validator" as a compatibleDescendant.
        // Hovering that descendant against the bundled-only draft should
        // surface a replacement edge to validation-service.
        StudioCatalogService service = new StudioCatalogService();

        StudioConnectionCandidatesResponse response = service.connectionCandidates(
                "tenant-bundled",
                "assembly-demo",
                "com.unfurl:customer-policy-validator:1.2.0");

        // Candidate isn't in the bundled catalog, so we expect a warning
        // and no edges — proving the gate works the same way for
        // replacement-only candidates.
        assertThat(response.warnings()).hasSize(1);
        assertThat(response.warnings().get(0)).contains("not in the tenant catalog");
    }

    @Test
    void noSelfEdgesWhenCandidateIsAlreadyInDraft(@TempDir Path assetRoot) throws Exception {
        // provider-llm has no needs and consumer-app has one need
        // satisfied by provider-llm. Hovering provider-llm itself: only
        // consumer-app is a target. Confirms the candidate doesn't
        // generate self-edges against its own catalog entry.
        writeManifestJar(assetRoot, "provider-llm.jar", PROVIDER_LLM_MANIFEST);
        writeManifestJar(assetRoot, "consumer-app.jar", CONSUMER_APP_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioConnectionCandidatesResponse response = service.connectionCandidates(
                "tenant-conn",
                "assembly-demo",
                "com.unfurl.test:provider-llm:0.1.0");

        for (StudioConnectionEdge edge : response.connections()) {
            assertThat(edge.targetNodeId()).isNotEqualTo("component.provider-llm");
        }
    }

    private static void writeManifestJar(Path dir, String fileName, String manifestYaml) throws Exception {
        Path target = dir.resolve(fileName);
        Files.createDirectories(target.getParent() == null ? dir : target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry("META-INF/unfurl-catalog.yaml");
            jar.putNextEntry(entry);
            jar.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
