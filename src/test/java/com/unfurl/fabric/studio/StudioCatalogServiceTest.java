package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StudioCatalogServiceTest {

    @Test
    void listsCatalogVisualsPerTenant() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogVisualsResponse first = service.listCatalogVisuals("tenant-a");
        StudioCatalogVisualsResponse second = service.listCatalogVisuals("tenant-b");

        assertThat(first.entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .containsExactly("com.unfurl:validation-service:1.1.0", "com.unfurl:storage-s3:1.2.0");
        assertThat(second.entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .containsExactly("com.unfurl:validation-service:1.1.0", "com.unfurl:storage-s3:1.2.0");
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

    @Test
    void projectsDynamicDcpGraphForTenantAssembly() {
        StudioCatalogService service = new StudioCatalogService();
        service.createAssembly("tenant-a", new StudioCreateAssemblyRequest(
                "assembly-checkout",
                "Checkout Platform",
                "kubernetes-prod"));

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection("tenant-a", "assembly-checkout");

        assertThat(projection.tenantId()).isEqualTo("tenant-a");
        assertThat(projection.assemblyId()).isEqualTo("assembly-checkout");
        assertThat(projection.compositionMode()).isEqualTo("DYNAMIC");
        assertThat(projection.nodes())
                .extracting(StudioDynamicDcpNode::level)
                .contains("PARENT", "ASSEMBLY", "CHILD");
        assertThat(projection.nodes())
                .filteredOn(StudioDynamicDcpNode::replacementAllowed)
                .extracting(StudioDynamicDcpNode::catalogEntryId)
                .contains("com.unfurl:validation-service:1.1.0", "com.unfurl:storage-s3:1.2.0");
        assertThat(projection.edges())
                .extracting(StudioDynamicDcpEdge::relationship)
                .contains("CONTAINS", "REQUIRES");
    }

    @Test
    void projectsRecursiveDcpSubtreeFromDcpClaimMetadata(@TempDir Path assetRoot) throws Exception {
        Path manifest = assetRoot.resolve("demo/META-INF/unfurl-studio-visuals.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {
                  "entries": [
                    {
                      "catalogEntryId": "com.unfurl:city:1.0.0",
                      "claimHash": "sha256:city",
                      "artifactSha256": "sha256:city-artifact",
                      "visualManifest": {"ports": [{"kind": "OFFER", "mapsTo": "claim.offers.city.run"}]},
                      "dynamicComposition": {
                        "dcpType": "CITY",
                        "level": "CITY",
                        "containsCatalogEntryIds": ["com.unfurl:colony:1.0.0"]
                      }
                    },
                    {
                      "catalogEntryId": "com.unfurl:colony:1.0.0",
                      "claimHash": "sha256:colony",
                      "artifactSha256": "sha256:colony-artifact",
                      "visualManifest": {"ports": [{"kind": "OFFER", "mapsTo": "claim.offers.colony.run"}]},
                      "dynamicComposition": {
                        "dcpType": "COLONY",
                        "level": "COLONY",
                        "containsCatalogEntryIds": ["com.unfurl:home:1.0.0"]
                      }
                    },
                    {
                      "catalogEntryId": "com.unfurl:home:1.0.0",
                      "claimHash": "sha256:home",
                      "artifactSha256": "sha256:home-artifact",
                      "visualManifest": {"ports": [{"kind": "OFFER", "mapsTo": "claim.offers.home.run"}]},
                      "dynamicComposition": {
                        "dcpType": "HOME",
                        "level": "HOME"
                      }
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        StudioCatalogService service = new StudioCatalogService(null, assetRoot);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection("tenant-a", "assembly-city");

        assertThat(projection.nodes())
                .extracting(StudioDynamicDcpNode::level)
                .contains("PARENT", "ASSEMBLY", "CITY", "COLONY", "HOME");
        StudioDynamicDcpNode city = projection.nodes().stream()
                .filter(node -> "CITY".equals(node.level()))
                .findFirst()
                .orElseThrow();
        StudioDynamicDcpNode colony = projection.nodes().stream()
                .filter(node -> "COLONY".equals(node.level()))
                .findFirst()
                .orElseThrow();
        StudioDynamicDcpNode home = projection.nodes().stream()
                .filter(node -> "HOME".equals(node.level()))
                .findFirst()
                .orElseThrow();
        assertThat(city.depth()).isEqualTo(2);
        assertThat(colony.parentNodeId()).isEqualTo(city.nodeId());
        assertThat(home.parentNodeId()).isEqualTo(colony.nodeId());
        assertThat(city.compatibleDescendants()).isEmpty();
        assertThat(projection.edges())
                .extracting(edge -> edge.fromNodeId() + "->" + edge.toNodeId())
                .contains(city.nodeId() + "->" + colony.nodeId(), colony.nodeId() + "->" + home.nodeId());
    }

    @Test
    void providesReplacementCandidatesFromDynamicDcpProjection() {
        StudioCatalogService service = new StudioCatalogService();

        StudioReplacementCandidatesResponse response = service.replacementCandidates(
                "tenant-a",
                "assembly-demo",
                "component.validation-service");

        assertThat(response.componentNodeId()).isEqualTo("component.validation-service");
        assertThat(response.candidates())
                .extracting(StudioReplacementCandidate::catalogEntryId)
                .contains(
                        "com.unfurl:validation-service:1.1.0",
                        "com.unfurl:customer-policy-validator:1.2.0",
                        "com.unfurl:fraud-only-validator:1.0.0");
        assertThat(response.candidates())
                .filteredOn(candidate -> "BLOCKED".equals(candidate.status()))
                .singleElement()
                .extracting(StudioReplacementCandidate::reason)
                .asString()
                .contains("validate.inventory");
    }

    @Test
    void persistsTenantCatalogAssembliesAndLayouts(@TempDir Path dir) {
        StudioStateStore store = new StudioStateStore(dir.resolve("studio-state.json"));
        StudioCatalogService first = new StudioCatalogService(store);

        first.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("payment.jar", ""))));
        first.createAssembly("tenant-a", new StudioCreateAssemblyRequest(
                "assembly-checkout",
                "Checkout Platform",
                "kubernetes-prod"));
        first.saveLayout("tenant-a", "assembly-checkout", new StudioLayoutStateRequest(
                "Exploded",
                "CHILD_DCP",
                "payment",
                Map.of("distance", 5.5),
                List.of("inspect payment replacement")));

        StudioCatalogService second = new StudioCatalogService(store);

        assertThat(second.listCatalogVisuals("tenant-a").entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains("uploaded:payment.jar");
        assertThat(second.listAssemblies("tenant-a").assemblies())
                .extracting(StudioAssemblySummary::assemblyId)
                .contains("assembly-checkout");
        assertThat(second.layout("tenant-a", "assembly-checkout"))
                .satisfies(layout -> {
                    assertThat(layout.activeView()).isEqualTo("Exploded");
                    assertThat(layout.semanticZoomLevel()).isEqualTo("CHILD_DCP");
                    assertThat(layout.selectedSurface()).isEqualTo("payment");
                    assertThat(layout.annotations()).contains("inspect payment replacement");
                });
    }

    @Test
    void exposesHashPinnedVisualAssets() {
        StudioCatalogService service = new StudioCatalogService();

        StudioVisualAsset asset = service.visualAsset("tenant-a", "validation-service-model");
        StudioVisualAsset missing = service.visualAsset("tenant-a", "missing-model");

        assertThat(asset.status()).isEqualTo("HASH_PINNED");
        assertThat(asset.path()).isEqualTo("META-INF/visual/validation-service.glb");
        assertThat(asset.mediaType()).isEqualTo("model/gltf-binary");
        assertThat(asset.sha256()).startsWith("sha256:");
        assertThat(asset.url()).contains("sha256=");
        assertThat(service.visualAsset("tenant-a", "validation-service-thumbnail"))
                .satisfies(thumbnail -> {
                    assertThat(thumbnail.status()).isEqualTo("HASH_PINNED");
                    assertThat(thumbnail.path()).isEqualTo("META-INF/visual/validation-service-thumbnail.png");
                    assertThat(thumbnail.mediaType()).isEqualTo("image/png");
                });
        assertThat(missing.status()).isEqualTo("FALLBACK_REQUIRED");
        assertThat(missing.warning()).contains("not present");
    }

    @Test
    void servesBundledFixtureAssetsByDefault() {
        StudioCatalogService service = new StudioCatalogService();
        StudioVisualAsset metadata = service.visualAsset("tenant-a", "storage-s3-model");

        assertThat(service.visualAssetContent("tenant-a", "storage-s3-model", metadata.sha256()))
                .hasValueSatisfying(content -> {
                    assertThat(content.mediaType()).isEqualTo("model/gltf-binary");
                    assertThat(new String(content.bytes(), StandardCharsets.UTF_8))
                            .isEqualTo("asset:storage-s3:META-INF/visual/storage-s3.glb");
                });
    }

    @Test
    void scansPackageVisualAssetsFromConfiguredAssetRoot(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("packages/payment/META-INF/visual/payment.glb");
        Path manifest = dir.resolve("packages/payment/META-INF/unfurl-studio-visuals.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "real package glb bytes", StandardCharsets.UTF_8);
        Files.writeString(manifest, """
                {
                  "entries": [
                    {
                      "catalogEntryId": "com.unfurl:payment-adapter:2.0.0",
                      "claimHash": "sha256:claim-payment",
                      "artifactSha256": "sha256:artifact-payment",
                      "visualManifest": {
                        "fallbackShape": { "kind": "CUBE", "category": "WORKFLOW" },
                        "ports": [],
                        "interactions": { "draggable": true, "connectable": true, "inspectable": true }
                      },
                      "dynamicComposition": {
                        "compositionMode": "STATIC",
                        "dcpType": "COMPONENT"
                      },
                      "assets": [
                        {
                          "assetId": "payment-model",
                          "path": "packages/payment/META-INF/visual/payment.glb",
                          "mediaType": "model/gltf-binary"
                        }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        StudioCatalogService service = new StudioCatalogService(null, dir);

        assertThat(service.listCatalogVisuals("tenant-a").entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains("com.unfurl:payment-adapter:2.0.0", "com.unfurl:storage-s3:1.2.0");
        StudioVisualAsset asset = service.visualAsset("tenant-a", "payment-model");

        assertThat(asset.status()).isEqualTo("HASH_PINNED");
        assertThat(asset.path()).isEqualTo("packages/payment/META-INF/visual/payment.glb");
        assertThat(asset.sha256()).startsWith("sha256:");
        assertThat(service.visualAssetContent("tenant-a", "payment-model", asset.sha256()))
                .hasValueSatisfying(content ->
                        assertThat(new String(content.bytes(), StandardCharsets.UTF_8))
                                .isEqualTo("real package glb bytes"));
    }

    @Test
    void coordinatesCollaborativeSessionsWithOptimisticRevisions() throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-demo",
                        "sha256:catalog",
                        "needs-checkout",
                        "trust-prod",
                        "cand-initial",
                        "alice",
                        "Alice"));

        StudioIntentRequest alice = new StudioIntentRequest();
        alice.tenantId = "tenant-a";
        alice.assemblyId = "assembly-demo";
        alice.sessionId = created.session().sessionId();
        alice.baseRevision = 0;
        alice.type = "REPLACE_COMPONENT";
        alice.collaboratorId = "alice";
        alice.put("newCatalogEntryId", "com.unfurl:validation-service:1.1.0");

        StudioIntentRequest bob = new StudioIntentRequest();
        bob.tenantId = "tenant-a";
        bob.assemblyId = "assembly-demo";
        bob.sessionId = created.session().sessionId();
        bob.baseRevision = 0;
        bob.type = "CONNECT";
        bob.collaboratorId = "bob";

        try (StudioSessionEventSubscription subscription = service.subscribeSessionEvents(
                "tenant-a",
                "assembly-demo",
                created.session().sessionId())) {
            StudioSessionEvent initial = subscription.poll(1, TimeUnit.SECONDS);
            assertThat(initial).isNotNull();
            assertThat(initial.session().sceneRevision()).isZero();

            StudioIntentResponse accepted = service.applyIntent("tenant-a", "assembly-demo", created.session().sessionId(), alice);
            StudioIntentResponse stale = service.applyIntent("tenant-a", "assembly-demo", created.session().sessionId(), bob);
            StudioSessionEvent event = subscription.poll(1, TimeUnit.SECONDS);

            assertThat(accepted.status()).isEqualTo("VALID");
            assertThat(accepted.newRevision()).isEqualTo(1);
            assertThat(accepted.session().intentLog()).singleElement()
                    .extracting(StudioIntentRecord::collaboratorId)
                    .isEqualTo("alice");
            assertThat(stale.status()).isEqualTo("STALE_REVISION");
            assertThat(stale.expectedRevision()).isEqualTo(1);
            assertThat(stale.receivedRevision()).isEqualTo(0);
            assertThat(event).isNotNull();
            assertThat(event.eventId()).endsWith(":1");
            assertThat(event.session().sceneRevision()).isEqualTo(1);
            assertThat(event.session().collaborators())
                    .extracting(StudioCollaborator::collaboratorId)
                    .contains("alice");
        }
    }

    @Test
    void peerInstanceCanSubscribeBeforeSessionIsLocal() throws Exception {
        InMemoryStudioSessionEventBus sharedBus = new InMemoryStudioSessionEventBus();
        StudioCatalogService writer = new StudioCatalogService(null, null, sharedBus);
        StudioCatalogService reader = new StudioCatalogService(null, null, sharedBus);
        StudioCreateDraftCompositionResponse created = writer.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-demo",
                        "sha256:catalog",
                        "needs-checkout",
                        "trust-prod",
                        "cand-initial",
                        "alice",
                        "Alice"));

        try (StudioSessionEventSubscription subscription = reader.subscribeSessionEvents(
                "tenant-a",
                "assembly-demo",
                created.session().sessionId())) {
            assertThat(subscription.poll(100, TimeUnit.MILLISECONDS)).isNull();

            StudioIntentRequest intent = new StudioIntentRequest();
            intent.tenantId = "tenant-a";
            intent.assemblyId = "assembly-demo";
            intent.sessionId = created.session().sessionId();
            intent.baseRevision = 0;
            intent.type = "REPLACE_COMPONENT";
            intent.collaboratorId = "alice";
            intent.put("newCatalogEntryId", "com.unfurl:storage-s3:1.2.0");
            writer.applyIntent("tenant-a", "assembly-demo", created.session().sessionId(), intent);

            StudioSessionEvent event = subscription.poll(1, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.session().sessionId()).isEqualTo(created.session().sessionId());
            assertThat(event.session().sceneRevision()).isEqualTo(1);
        }
    }

    @Test
    void servesBinaryAssetOnlyWhenPinnedHashMatches(@TempDir Path dir) throws Exception {
        Path asset = dir.resolve("META-INF/visual/validation-service.glb");
        Files.createDirectories(asset.getParent());
        Files.writeString(
                asset,
                "asset:validation-service:META-INF/visual/validation-service.glb",
                StandardCharsets.UTF_8);
        StudioCatalogService service = new StudioCatalogService(null, dir);
        StudioVisualAsset metadata = service.visualAsset("tenant-a", "validation-service-model");

        assertThat(service.visualAssetContent("tenant-a", "validation-service-model", metadata.sha256()))
                .hasValueSatisfying(content -> {
                    assertThat(content.mediaType()).isEqualTo("model/gltf-binary");
                    assertThat(new String(content.bytes(), StandardCharsets.UTF_8))
                            .isEqualTo("asset:validation-service:META-INF/visual/validation-service.glb");
                });
        assertThat(service.visualAssetContent("tenant-a", "validation-service-model", "sha256:wrong"))
                .isEmpty();
    }
}
