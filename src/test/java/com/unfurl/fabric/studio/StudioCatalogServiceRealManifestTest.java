package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link StudioCatalogService} picks up real portfolio
 * components from staged JARs containing {@code META-INF/unfurl-catalog.yaml}
 * — the same manifest format the 8 portfolio modules ship. Bridges fabric's
 * production {@code CatalogScanner} into the Studio's visual catalog shape.
 */
class StudioCatalogServiceRealManifestTest {

    private static final String ADVISOR_ANTHROPIC_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:advisor:anthropic
                name: unfurl-fabric-advisor-anthropic
                kind: INTELLIGENT_COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Anthropic adapter
                concerns:
                  - concern: ai.llm.anthropic
                    description: Claude completions
                boundary_principles:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs: []
              offers:
                - capability: ai.llm.anthropic
                  description: Claude completions
                  consumer_access: NAMED_COMPONENTS_ONLY
                  stability: STABLE
                  version: 0.1.0
                  metered: true
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.fabric.advisor:unfurl-fabric-advisor-anthropic:0.1.0
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

    private static final String FOUNDRY_RAG_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:foundry:rag
                name: foundry-substrate-rag
                kind: INTELLIGENT_COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Retrieval-augmented generation substrate
                concerns:
                  - concern: rag.search
                    description: Vector retrieval
                boundary_principles:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs: []
              offers:
                - capability: rag.search
                  description: Retrieval
                  consumer_access: NAMED_COMPONENTS_ONLY
                  stability: EVOLVING
                  version: 0.1.0
                  metered: false
                - capability: rag.embed
                  description: Embeddings
                  consumer_access: NAMED_COMPONENTS_ONLY
                  stability: EVOLVING
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.foundry.substrate:foundry-substrate-rag:0.1.0
                packaging: jar
                source: catalog
              binding:
                default_mode: IN_PROCESS
                supported_modes: [IN_PROCESS]
              component_shape_profile:
                default_shape: IN_PROCESS_LIBRARY
                supported_shapes: [IN_PROCESS_LIBRARY]
                shape_runtime: {}
            """;

    @Test
    void surfacesRealManifestsFromAssetRoot(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "unfurl-fabric-advisor-anthropic.jar", ADVISOR_ANTHROPIC_MANIFEST);
        writeManifestJar(assetRoot, "foundry-substrate-rag.jar", FOUNDRY_RAG_MANIFEST);

        StudioCatalogService service = new StudioCatalogService(null, assetRoot);
        StudioCatalogVisualsResponse response = service.listCatalogVisuals("tenant-real");

        assertThat(response.entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains(
                        "com.unfurl.fabric.advisor:unfurl-fabric-advisor-anthropic:0.1.0",
                        "com.unfurl.foundry.substrate:foundry-substrate-rag:0.1.0");
    }

    @Test
    void mapsDeploymentShapeIntoVisualCategoryBadge(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "unfurl-fabric-advisor-anthropic.jar", ADVISOR_ANTHROPIC_MANIFEST);
        writeManifestJar(assetRoot, "foundry-substrate-rag.jar", FOUNDRY_RAG_MANIFEST);

        StudioCatalogService service = new StudioCatalogService(null, assetRoot);
        Map<String, StudioVisualCatalogEntry> byId = byCatalogEntryId(
                service.listCatalogVisuals("tenant-real"));

        // Real-manifest projection synthesizes the visual descriptor from
        // componentShapeProfile.defaultShape; no .glb required.
        Map<?, ?> anthropicFallback = (Map<?, ?>) byId
                .get("com.unfurl.fabric.advisor:unfurl-fabric-advisor-anthropic:0.1.0")
                .visualManifest()
                .get("fallbackShape");
        assertThat(anthropicFallback.get("category")).isEqualTo("MANAGED_EXTERNAL_ADAPTER");
        // MANAGED_EXTERNAL_ADAPTER renders as SHIELD per fallbackShapeKindFor mapping.
        assertThat(anthropicFallback.get("kind")).isEqualTo("SHIELD");

        Map<?, ?> ragFallback = (Map<?, ?>) byId
                .get("com.unfurl.foundry.substrate:foundry-substrate-rag:0.1.0")
                .visualManifest()
                .get("fallbackShape");
        assertThat(ragFallback.get("category")).isEqualTo("IN_PROCESS_LIBRARY");
        // IN_PROCESS_LIBRARY renders as CUBE.
        assertThat(ragFallback.get("kind")).isEqualTo("CUBE");
    }

    @Test
    void distinctDeploymentShapesGetDistinctFallbackPrimitives(@TempDir Path assetRoot) throws Exception {
        // Five renderer primitives must each be reachable from at least one
        // deployment shape; we cover three of them with our 8-module catalog
        // (CUBE/IN_PROCESS_LIBRARY, CYLINDER/SPRING_BOOT_SERVICE,
        // SHIELD/MANAGED_EXTERNAL_ADAPTER). The shapes that don't show up
        // here (SPHERE/REMOTE_MICROSERVICE, GEAR/CONTAINERIZED_SERVICE,
        // CUBE/MODULAR_MONOLITH_MODULE, CYLINDER/STANDALONE_JAVA_APP) are
        // verified inline via shapeManifest()/expectedKindFor below.
        for (Object[] row : new Object[][] {
                {"IN_PROCESS_LIBRARY", "CUBE"},
                {"MODULAR_MONOLITH_MODULE", "CUBE"},
                {"STANDALONE_JAVA_APP", "CYLINDER"},
                {"SPRING_BOOT_SERVICE", "CYLINDER"},
                {"REMOTE_MICROSERVICE", "SPHERE"},
                {"CONTAINERIZED_SERVICE", "GEAR"},
                {"MANAGED_EXTERNAL_ADAPTER", "SHIELD"}
        }) {
            String shape = (String) row[0];
            String expectedKind = (String) row[1];
            Path scratch = Files.createTempDirectory(assetRoot, "shape-" + shape.toLowerCase() + "-");
            writeManifestJar(scratch, "demo.jar", shapeManifest(shape));
            StudioCatalogService service = new StudioCatalogService(null, scratch);
            Map<?, ?> fallback = (Map<?, ?>) service.listCatalogVisuals("t").entries().get(0)
                    .visualManifest()
                    .get("fallbackShape");
            assertThat(fallback.get("kind"))
                    .as("kind for shape %s", shape)
                    .isEqualTo(expectedKind);
            assertThat(fallback.get("category"))
                    .as("category for shape %s", shape)
                    .isEqualTo(shape);
        }
    }

    private static Map<String, StudioVisualCatalogEntry> byCatalogEntryId(StudioCatalogVisualsResponse response) {
        Map<String, StudioVisualCatalogEntry> out = new java.util.HashMap<>();
        for (StudioVisualCatalogEntry entry : response.entries()) {
            out.put(entry.catalogEntryId(), entry);
        }
        return out;
    }

    private static String shapeManifest(String shape) {
        return String.format("""
                claim:
                  identity:
                    uri: urn:unfurl:demo:%s
                    name: demo-%s
                    kind: COMPONENT
                    version: 0.1.0
                    publisher: Unfurl
                  domain:
                    summary: demo
                    concerns:
                      - concern: demo.do
                        description: demo
                    boundary_principles:
                      - "deterministic boundary"
                  refusals: []
                  dependencies:
                    needs: []
                  offers:
                    - capability: demo.do
                      description: demo
                      consumer_access: NAMED_COMPONENTS_ONLY
                      stability: STABLE
                      version: 0.1.0
                      metered: false
                catalog:
                  lifecycle:
                    status: ACTIVE
                  artifact:
                    coordinates: com.unfurl.demo:demo-%s:0.1.0
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: IN_PROCESS
                    supported_modes: [IN_PROCESS]
                  component_shape_profile:
                    default_shape: %s
                    supported_shapes: [%s]
                    shape_runtime: {}
                """, shape.toLowerCase(), shape.toLowerCase(), shape.toLowerCase(), shape, shape);
    }

    @Test
    void exposesOfferedCapabilitiesAsPorts(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "foundry-substrate-rag.jar", FOUNDRY_RAG_MANIFEST);

        StudioCatalogService service = new StudioCatalogService(null, assetRoot);
        StudioVisualCatalogEntry entry = service.listCatalogVisuals("tenant-real").entries().stream()
                .filter(e -> e.catalogEntryId().equals(
                        "com.unfurl.foundry.substrate:foundry-substrate-rag:0.1.0"))
                .findFirst()
                .orElseThrow();

        Object ports = entry.visualManifest().get("ports");
        assertThat(ports).isInstanceOf(List.class);
        // Both rag.search + rag.embed should be surfaced as ports.
        assertThat((List<?>) ports).hasSize(2);
    }

    @Test
    void realManifestComponentsAreAssemblable(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "unfurl-fabric-advisor-anthropic.jar", ADVISOR_ANTHROPIC_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-real", "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-real", "assembly-demo", "sha256:catalog",
                        "needs-checkout", "trust-prod", "cand-initial", "alice", "Alice"));

        StudioIntentRequest intent = new StudioIntentRequest();
        intent.tenantId = "tenant-real";
        intent.assemblyId = "assembly-demo";
        intent.sessionId = created.session().sessionId();
        intent.baseRevision = 0;
        intent.type = "ADD_COMPONENT";
        intent.collaboratorId = "alice";
        intent.put("catalogEntryId", "com.unfurl.fabric.advisor:unfurl-fabric-advisor-anthropic:0.1.0");

        StudioIntentResponse response = service.applyIntent(
                "tenant-real", "assembly-demo", created.session().sessionId(), intent);

        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.updatedCandidateId())
                .isEqualTo("com.unfurl.fabric.advisor:unfurl-fabric-advisor-anthropic:0.1.0");
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
