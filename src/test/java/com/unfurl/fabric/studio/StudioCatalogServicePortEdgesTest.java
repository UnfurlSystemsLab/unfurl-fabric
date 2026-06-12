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
 * Verifies {@link StudioCatalogService#dynamicDcpProjection} now ships
 * port-level connection edges derived from declared needs ↔ offers. The
 * Studio scene's pipe rendering reads these directly; they're the
 * truth of "which component plugs into which other component" for the
 * current draft.
 */
class StudioCatalogServicePortEdgesTest {

    private static final String PROVIDER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:provider
                name: provider
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Provider that offers ai.chat.completion
                concerns:
                  - concern: ai.chat.completion
                    description: chat
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs: []
              offers:
                - capability: ai.chat.completion
                  description: chat
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:provider:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    private static final String CONSUMER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:consumer
                name: consumer
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Consumer that needs ai.chat.completion
                concerns:
                  - concern: app.checkout
                    description: checkout
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs:
                  - "ai.chat.completion@v1"
              offers:
                - capability: app.checkout
                  description: checkout
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:consumer:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    /**
     * Same as {@link #CONSUMER_MANIFEST} but its only need carries
     * {@code ?owner=host}, so the matcher should skip it — the
     * customer's Spring context is what supplies the capability and
     * an in-scene pipe would be misleading.
     */
    private static final String HOST_OWNED_CONSUMER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:host-owned-consumer
                name: host-owned-consumer
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Consumer whose only need is host-owned
                concerns:
                  - concern: app.checkout
                    description: checkout
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs:
                  - "ai.chat.completion@v1?owner=host"
              offers:
                - capability: app.checkout
                  description: checkout
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:host-owned-consumer:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    private static final String SUBSTRATE_OWNED_CONSUMER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:substrate-owned-consumer
                name: substrate-owned-consumer
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Consumer whose state store is supplied by the Unfurl substrate
                concerns:
                  - concern: app.checkout
                    description: checkout
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs:
                  - "state-store@^1?substrate=true&provider=postgres"
              offers:
                - capability: app.checkout
                  description: checkout
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:substrate-owned-consumer:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    private static final String UNTAGGED_UNFURL_SUBSTRATE_CONSUMER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:untagged-substrate-consumer
                name: untagged-substrate-consumer
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Consumer whose Spring AI client is supplied by the runtime substrate
                concerns:
                  - concern: app.agent
                    description: agent
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs:
                  - "spring-ai.chat-client@^1"
              offers:
                - capability: app.agent
                  description: agent
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:untagged-substrate-consumer:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    /**
     * Consumer that needs TWO distinct capabilities. When both are
     * satisfied by the same provider (which offers both), the matcher
     * must emit two distinct edges.
     */
    private static final String MULTI_NEEDS_CONSUMER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:multi-needs-consumer
                name: multi-needs-consumer
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Consumer with two peer needs
                concerns:
                  - concern: app.checkout
                    description: checkout
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs:
                  - "ai.chat.completion@v1"
                  - "ai.chat.embedding@v1"
              offers:
                - capability: app.checkout
                  description: checkout
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:multi-needs-consumer:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    private static final String MULTI_OFFERS_PROVIDER_MANIFEST = """
            claim:
              identity:
                uri: urn:unfurl:test:multi-provider
                name: multi-provider
                kind: COMPONENT
                version: 0.1.0
                publisher: Unfurl
              domain:
                summary: Provider that offers chat AND embedding
                concerns:
                  - concern: ai.chat.completion
                    description: chat
                boundaryPrinciples:
                  - "deterministic boundary"
              refusals: []
              dependencies:
                needs: []
              offers:
                - capability: ai.chat.completion
                  description: chat
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
                - capability: ai.chat.embedding
                  description: embeddings
                  consumerAccess: ANY
                  stability: STABLE
                  version: 0.1.0
                  metered: false
            catalog:
              lifecycle:
                status: ACTIVE
              artifact:
                coordinates: com.unfurl.test:multi-provider:0.1.0
                packaging: jar
                source: catalog
              binding:
                defaultMode: IN_PROCESS
                supportedModes: [IN_PROCESS]
              componentShapeProfile:
                defaultShape: IN_PROCESS_LIBRARY
                supportedShapes: [IN_PROCESS_LIBRARY]
                shapeRuntime: {}
            """;

    @Test
    void peerOfferToNeedMatchSurfacesAsConnectionEdge(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "provider.jar", PROVIDER_MANIFEST);
        writeManifestJar(assetRoot, "consumer.jar", CONSUMER_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-edge-test", "assembly-demo");

        // Filter to peer-derived edges only (the catalog also contains
        // bundled fixtures that don't peer-match with these test jars).
        List<StudioPortConnectionEdge> peer = projection.connections().stream()
                .filter(edge -> edge.targetNodeId().contains("consumer")
                        && edge.sourceNodeId().contains("provider"))
                .toList();
        assertThat(peer).hasSize(1);
        StudioPortConnectionEdge edge = peer.get(0);
        assertThat(edge.capability()).isEqualTo("ai.chat.completion");
        assertThat(edge.sourcePortId()).isEqualTo("ai-chat-completion");
        assertThat(edge.targetPortId()).isEqualTo("need-ai-chat-completion");
        assertThat(edge.status()).isEqualTo("ALLOWED");
        assertThat(edge.reason()).contains("ai.chat.completion");
    }

    @Test
    void hostOwnedNeedIsSkipped(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "provider.jar", PROVIDER_MANIFEST);
        writeManifestJar(assetRoot, "host-owned-consumer.jar", HOST_OWNED_CONSUMER_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-host-owned-test", "assembly-demo");

        // The host-owned consumer's only need carries ?owner=host, so
        // the matcher must skip it — no pipe between provider and
        // host-owned-consumer should appear, even though the capability
        // names match.
        boolean anyEdgeToHostOwnedConsumer = projection.connections().stream()
                .anyMatch(edge -> edge.targetNodeId().contains("host-owned-consumer"));
        assertThat(anyEdgeToHostOwnedConsumer)
                .as("?owner=host needs must not produce peer edges")
                .isFalse();
    }

    @Test
    void substrateOwnedNeedSurfacesAsSubstratePortAndConnection(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "substrate-owned-consumer.jar", SUBSTRATE_OWNED_CONSUMER_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-substrate-owned-test", "assembly-demo");

        assertThat(projection.substratePorts())
                .extracting(StudioSubstratePort::capability)
                .contains("state-store");
        StudioSubstratePort port = projection.substratePorts().stream()
                .filter(candidate -> candidate.capability().equals("state-store"))
                .findFirst()
                .orElseThrow();
        assertThat(port.portId()).isEqualTo("substrate:state-store");
        assertThat(port.provider()).isEqualTo("postgres");

        List<StudioPortConnectionEdge> substrateEdges = projection.connections().stream()
                .filter(edge -> edge.sourceNodeId().equals("substrate:runtime")
                        && edge.targetNodeId().contains("substrate-owned-consumer"))
                .toList();
        assertThat(substrateEdges).hasSize(1);
        StudioPortConnectionEdge edge = substrateEdges.get(0);
        assertThat(edge.sourcePortId()).isEqualTo("substrate:state-store");
        assertThat(edge.targetPortId()).isEqualTo("need-state-store");
        assertThat(edge.capability()).isEqualTo("state-store");
    }

    @Test
    void knownUnfurlRuntimeNeedFallsBackToSubstrateWhenNoComponentOffersIt(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "untagged-substrate-consumer.jar", UNTAGGED_UNFURL_SUBSTRATE_CONSUMER_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-substrate-fallback-test", "assembly-demo");

        assertThat(projection.substratePorts())
                .extracting(StudioSubstratePort::capability)
                .contains("spring-ai.chat-client");
        assertThat(projection.connections())
                .anySatisfy(edge -> {
                    assertThat(edge.sourceNodeId()).isEqualTo("substrate:runtime");
                    assertThat(edge.sourcePortId()).isEqualTo("substrate:spring-ai-chat-client");
                    assertThat(edge.targetNodeId()).contains("untagged-substrate-consumer");
                    assertThat(edge.capability()).isEqualTo("spring-ai.chat-client");
                });
    }

    @Test
    void noSelfEdgeEvenWhenAComponentOffersWhatItNeeds(@TempDir Path assetRoot) throws Exception {
        // Multi-offers provider offers ai.chat.completion. We pair it
        // with a (separate) consumer that needs it. The provider must
        // not appear as both source and target of any single edge.
        writeManifestJar(assetRoot, "multi-provider.jar", MULTI_OFFERS_PROVIDER_MANIFEST);
        writeManifestJar(assetRoot, "consumer.jar", CONSUMER_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-self-edge-test", "assembly-demo");

        for (StudioPortConnectionEdge edge : projection.connections()) {
            assertThat(edge.sourceNodeId())
                    .as("a node must not appear as both ends of a single edge")
                    .isNotEqualTo(edge.targetNodeId());
        }
    }

    @Test
    void multiCapabilityProviderSatisfiesMultipleNeedsOfOneConsumer(@TempDir Path assetRoot) throws Exception {
        writeManifestJar(assetRoot, "multi-provider.jar", MULTI_OFFERS_PROVIDER_MANIFEST);
        writeManifestJar(assetRoot, "multi-needs-consumer.jar", MULTI_NEEDS_CONSUMER_MANIFEST);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-multi-test", "assembly-demo");

        List<StudioPortConnectionEdge> peerEdges = projection.connections().stream()
                .filter(edge -> edge.sourceNodeId().contains("multi-provider")
                        && edge.targetNodeId().contains("multi-needs-consumer"))
                .toList();
        assertThat(peerEdges)
                .as("two distinct capabilities should produce two distinct edges")
                .hasSize(2);
        assertThat(peerEdges)
                .extracting(StudioPortConnectionEdge::capability)
                .containsExactlyInAnyOrder("ai.chat.completion", "ai.chat.embedding");
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
