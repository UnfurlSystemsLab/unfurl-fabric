package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.ComponentKind;
import com.unfurl.dcp.claim.ConsumerAccess;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OfferInterface;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.dcp.projection.DcpProjectionProjector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;

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
                List.of(new StudioComponentArtifactDraft("payment.yaml", "", validClaimYaml("payment", "payment.process")))));

        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("VERIFIED");
                    assertThat(result.catalogEntryId()).isEqualTo("uploaded:payment.yaml");
                    assertThat(result.claimHash()).startsWith("sha256:");
                    assertThat(result.diagnostics()).isEmpty();
                });
        assertThat(response.catalog().entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains("uploaded:payment.yaml");
        assertThat(response.catalog().entries().stream()
                .filter(entry -> "uploaded:payment.yaml".equals(entry.catalogEntryId()))
                .findFirst()
                .orElseThrow()
                .visualManifest().toString())
                .contains("claim.offers.payment.process");
        assertThat(response.claimBundleArtifact())
                .satisfies(artifact -> {
                    assertThat(artifact.mediaType()).isEqualTo("application/zip");
                    assertThat(artifact.sha256()).startsWith("sha256:");
                    assertThat(artifact.url()).contains("/studio/tenants/tenant-a/catalog/admissions/");
                    assertThat(artifact.url()).contains("/claims.zip?sha256=");
                });
    }

    @Test
    void rejectsAdmissionWhenDcpClaimValidationFails() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("broken.yaml", "", """
                        identity:
                          uri: urn:unfurl:test:broken
                          name: broken
                          kind: COMPONENT
                          version: 1.0.0
                          publisher: Unfurl
                        metadata:
                          dcp_version: 0.1.0
                          claim_version: 1.0.0
                        """))));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("REJECTED");
                    assertThat(result.catalogEntryId()).isEmpty();
                    assertThat(result.diagnostics())
                            .extracting(StudioDcpDiagnostic::code)
                            .contains("CLAIM_MALFORMED", "DCP_VERSION_UNSUPPORTED");
                    assertThat(result.diagnostics())
                            .extracting(StudioDcpDiagnostic::path)
                            .contains("domain", "metadata.dcp_version", "refusals");
                });
        assertThat(response.catalog().entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .doesNotContain("uploaded:broken.yaml");
    }

    @Test
    void rejectsAdmissionWithoutUploadedClaimYaml() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("payment.jar", ""))));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("REJECTED");
                    assertThat(result.diagnostics()).singleElement()
                            .satisfies(diagnostic -> {
                                assertThat(diagnostic.code()).isEqualTo("CLAIM_MALFORMED");
                                assertThat(diagnostic.path()).isEqualTo("claim");
                                assertThat(diagnostic.message()).contains("DCP claim YAML is required");
                            });
                });
    }

    /**
     * Verifies Studio admission can derive claim YAML from a JAR's embedded catalog
     * manifest, matching the UI upload path used for Foundry and substrate artifacts.
     */
    @Test
    void admitsJarArtifactByReadingEmbeddedCatalogManifest(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        String artifactBase64 = Base64.getEncoder().encodeToString(jarWithManifest(
                dir,
                validClaimYaml("jar-payment", "payment.process")));

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("payment.jar", "", "", artifactBase64))));

        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("VERIFIED");
                    assertThat(result.catalogEntryId()).isEqualTo("uploaded:payment.jar");
                    assertThat(result.claimHash()).startsWith("sha256:");
                    assertThat(result.diagnostics()).isEmpty();
                });
        assertThat(response.catalog().entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains("uploaded:payment.jar");
    }

    /**
     * Verifies multi-file admission emits one downloadable ZIP that preserves each resolved
     * DCP claim as its own file with manifest and diagnostics metadata.
     */
    @Test
    void emitsDownloadableClaimBundleForMultiFileAdmission(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        String artifactBase64 = Base64.getEncoder().encodeToString(jarWithManifest(
                dir,
                validClaimYaml("jar-payment", "payment.authorize")));

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(
                        new StudioComponentArtifactDraft("payment.yaml", "", validClaimYaml("payment", "payment.process")),
                        new StudioComponentArtifactDraft("payment.jar", "", "", artifactBase64))));

        StudioExportArtifact bundle = response.claimBundleArtifact();
        assertThat(bundle).isNotNull();
        StudioAssetContent content = service.claimBundleContent("tenant-a", bundle.artifactId(), bundle.sha256())
                .orElseThrow();
        assertThat(content.mediaType()).isEqualTo("application/zip");
        assertThat(content.sha256()).isEqualTo(bundle.sha256());
        assertThat(zipEntries(content.bytes()))
                .contains(
                        "claims/01-payment-yaml.claim.yaml",
                        "claims/02-payment-jar.claim.yaml",
                        "admission-manifest.yaml",
                        "diagnostics.json");
        assertThat(service.claimBundleContent("tenant-a", bundle.artifactId(), "sha256:wrong"))
                .isEmpty();
    }

    /**
     * Verifies missing embedded manifests stay visible as structured DCP diagnostics
     * rather than admitting an archive with no catalog claim.
     */
    @Test
    void rejectsJarArtifactWhenEmbeddedCatalogManifestIsMissing(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        String artifactBase64 = Base64.getEncoder().encodeToString(jarWithoutManifest(dir));

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("payment.jar", "", "", artifactBase64))));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("REJECTED");
                    assertThat(result.diagnostics()).singleElement()
                            .satisfies(diagnostic -> {
                                assertThat(diagnostic.code()).isEqualTo("CLAIM_MALFORMED");
                                assertThat(diagnostic.path()).isEqualTo("artifact.META-INF/unfurl-catalog.yaml");
                                assertThat(diagnostic.message()).contains("missing META-INF/unfurl-catalog.yaml");
                            });
                });
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
    void projectsMergedSubstrateDcpClaimsThroughStudioAdapter() {
        URI workflow = URI.create("urn:unfurl:flow:workflow:order-flow");
        URI node = URI.create("urn:unfurl:flow:node:order-flow.authoring");
        URI agent = URI.create("urn:unfurl:foundry:agent:fabric-authoring");
        URI tool = URI.create("urn:unfurl:foundry:tool:catalog-search");
        Map<URI, Claim> claims = new LinkedHashMap<>();
        claims.put(workflow, dcpClaim(workflow, "order-flow", "WORKFLOW", "WORKFLOW", List.of(node), List.of()));
        claims.put(node, dcpClaim(node, "Authoring", "NODE", "ACTION", List.of(agent), List.of()));
        claims.put(agent, dcpClaim(agent, "fabric-authoring", "AGENT", "AGENT", List.of(tool), List.of("agent.run")));
        claims.put(tool, dcpClaim(tool, "catalog-search", "TOOL", "TOOL", List.of(), List.of("tool.call")));

        StudioDynamicDcpProjection projection = new StudioCatalogService().dynamicDcpProjection(
                "tenant-a", "assembly-flow", workflow, workflow, claims);

        assertThat(projection.nodes())
                .extracting(StudioDynamicDcpNode::level)
                .containsExactly("WORKFLOW", "NODE", "AGENT", "TOOL");
        StudioDynamicDcpNode toolNode = projection.nodes().stream()
                .filter(item -> "TOOL".equals(item.level()))
                .findFirst()
                .orElseThrow();
        StudioDynamicDcpNode agentNode = projection.nodes().stream()
                .filter(item -> "AGENT".equals(item.level()))
                .findFirst()
                .orElseThrow();
        assertThat(toolNode.depth()).isEqualTo(3);
        assertThat(toolNode.parentNodeId()).isEqualTo(agentNode.nodeId());
        assertThat(agentNode.capabilities()).containsExactly("agent.run");
        assertThat(projection.edges())
                .extracting(StudioDynamicDcpEdge::relationship)
                .containsOnly("CONTAINS");
        assertThat(projection.warnings()).isEmpty();
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
                List.of(new StudioComponentArtifactDraft("payment.yaml", "", validClaimYaml("payment", "payment.process")))));
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
                .contains("uploaded:payment.yaml");
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

    private Claim dcpClaim(
            URI uri,
            String label,
            String level,
            String dcpType,
            List<URI> children,
            List<String> capabilities
    ) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("level", level);
        extensions.put("dcpType", dcpType);
        extensions.put(DcpProjectionProjector.EXT_CONTAINS, children.stream().map(URI::toString).toList());
        return new Claim(
                new Identity(uri, label, ComponentKind.COMPONENT, "1.0.0", "Unfurl", URI.create("urn:unfurl")),
                null,
                List.of(),
                new Dependencies(List.of()),
                capabilities.stream()
                        .map(capability -> new Offer(
                                capability,
                                capability,
                                ConsumerAccess.ANY,
                                new OfferInterface(InterfaceKind.IN_PROCESS, Map.of()),
                                Stability.STABLE,
                                "1.0.0",
                                false,
                                null))
                        .toList(),
                null,
                null,
                new IntegrationPorts(Map.of()),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, extensions));
    }

    private static String validClaimYaml(String name, String capability) {
        return """
                identity:
                  uri: urn:unfurl:test:%s
                  name: %s
                  kind: COMPONENT
                  version: 1.0.0
                  publisher: Unfurl
                domain:
                  summary: %s component
                  concerns:
                    - concern: %s
                      description: Provides %s
                  boundary_principles:
                    - owns only the declared capability
                refusals:
                  - concern: unrelated.concern
                    rationale: This component deliberately owns only its declared capability.
                    owned_by: host
                dependencies:
                  needs: []
                offers:
                  - capability: %s
                    description: Provides %s
                    consumer_access: ANY
                    offer_interface:
                      interface_kind: IN_PROCESS
                      details: {}
                    stability: STABLE
                    version: 1.0.0
                    metered: false
                integration_ports:
                  ports: {}
                metadata:
                  dcp_version: 0.2.0
                  claim_version: 1.0.0
                  created_at: 1970-01-01T00:00:00Z
                """.formatted(name, name, name, capability, capability, capability, capability);
    }

    /**
     * Fixture helper: creates a minimal JAR carrying the Fabric catalog manifest entry
     * used by Studio admission.
     */
    private static byte[] jarWithManifest(Path dir, String manifestYaml) throws Exception {
        Path jar = dir.resolve("component-with-manifest.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            output.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return Files.readAllBytes(jar);
    }

    /**
     * Fixture helper: creates a valid JAR that intentionally lacks the embedded catalog
     * manifest so negative admission behavior can be asserted.
     */
    private static byte[] jarWithoutManifest(Path dir) throws Exception {
        Path jar = dir.resolve("component-without-manifest.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/placeholder.txt"));
            output.write("placeholder".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return Files.readAllBytes(jar);
    }

    /**
     * Fixture helper: lists ZIP entry names from an in-memory Studio export artifact so
     * tests can assert bundle structure without expanding files onto disk.
     */
    private static Set<String> zipEntries(byte[] bytes) throws Exception {
        Set<String> entries = new java.util.LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }
}
