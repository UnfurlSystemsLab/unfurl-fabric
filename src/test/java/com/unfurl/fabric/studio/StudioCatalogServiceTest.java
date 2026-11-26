package com.unfurl.fabric.studio;

import com.unfurl.fabric.matcher.MatchFailureReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.ClaimValidator;
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
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void verifiesAndAdmitsComponentClaims(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        String manifest = catalogManifestYaml("payment", "payment.process");

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(jarDraft(dir, "payment.jar", manifest))));

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
        assertThat(response.catalog().entries().stream()
                .filter(entry -> "uploaded:payment.jar".equals(entry.catalogEntryId()))
                .findFirst()
                .orElseThrow())
                .satisfies(entry -> {
                    assertThat(entry.visualManifest().toString()).contains("claim.offers.payment.process");
                    assertThat(entry.catalogManifest()).isEqualTo(manifest);
                    assertThat(entry.admitted()).isTrue();
                });
        assertThat(response.claimBundleArtifact())
                .satisfies(artifact -> {
                    assertThat(artifact.mediaType()).isEqualTo("application/zip");
                    assertThat(artifact.sha256()).startsWith("sha256:");
                    assertThat(artifact.url()).contains("/studio/tenants/tenant-a/catalog/admissions/");
                    assertThat(artifact.url()).contains("/claims.zip?sha256=");
                });
    }

    @Test
    void removesCatalogEntriesFromTenantCatalog() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogRemovalResponse response = service.removeCatalogEntry(
                "tenant-a",
                "com.unfurl:validation-service:1.1.0");

        assertThat(response.status()).isEqualTo("REMOVED");
        assertThat(response.catalogEntryId()).isEqualTo("com.unfurl:validation-service:1.1.0");
        assertThat(response.catalog().entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .doesNotContain("com.unfurl:validation-service:1.1.0")
                .contains("com.unfurl:storage-s3:1.2.0");
        assertThat(service.listCatalogVisuals("tenant-a").entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .doesNotContain("com.unfurl:validation-service:1.1.0");
        assertThat(response.diagnosticArtifacts()).hasSize(1);
    }

    @Test
    void savesAndLoadsCatalogAndAssemblySnapshots() {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogSnapshot catalogSnapshot = service.saveCatalogSnapshot("tenant-a");
        service.removeCatalogEntry("tenant-b", "com.unfurl:storage-s3:1.2.0");
        StudioCatalogVisualsResponse loadedCatalog = service.loadCatalogSnapshot("tenant-b", catalogSnapshot);

        assertThat(catalogSnapshot.diagnosticArtifacts()).hasSize(1);
        assertThat(loadedCatalog.entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .contains("com.unfurl:storage-s3:1.2.0");

        service.saveDraft("tenant-a", "assembly-checkout", new StudioSaveDraftRequest(
                "Checkout Platform",
                "needs-checkout",
                "kubernetes-prod",
                "CONTAINERIZED_SERVICE",
                "cand-abc123",
                3));
        service.saveLayout("tenant-a", "assembly-checkout", StudioLayoutStateRequest.of(
                "CHILD_DCP",
                "payment",
                Map.of("distance", 5.5),
                List.of("inspect payment replacement"),
                "FLOW", List.of("SECURITY"), "SECURITY", "THREE_D", "EXPLODED"));
        StudioCreateDraftCompositionResponse session = service.createDraftSession(
                "tenant-a",
                "assembly-checkout",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-checkout",
                        catalogSnapshot.catalogHash(),
                        "needs-checkout",
                        "",
                        "cand-abc123",
                        "operator",
                        "Operator"));

        StudioAssemblySnapshot assemblySnapshot = service.saveAssemblySnapshot("tenant-a", "assembly-checkout");
        StudioAssemblySnapshot loadedAssembly = service.loadAssemblySnapshot("tenant-b", "assembly-import", assemblySnapshot);

        assertThat(session.session().sessionId()).isNotBlank();
        assertThat(assemblySnapshot.diagnosticArtifacts()).hasSize(1);
        assertThat(loadedAssembly.tenantId()).isEqualTo("tenant-b");
        assertThat(loadedAssembly.assemblyId()).isEqualTo("assembly-import");
        assertThat(loadedAssembly.layout().selectedSurface()).isEqualTo("payment");
        assertThat(loadedAssembly.sessions()).singleElement()
                .satisfies(loaded -> {
                    assertThat(loaded.tenantId()).isEqualTo("tenant-b");
                    assertThat(loaded.assemblyId()).isEqualTo("assembly-import");
                    assertThat(loaded.sessionId()).isEqualTo(session.session().sessionId());
                });
    }

    /**
     * Regression (LLD-fabric-studio-api, Assembly snapshots): a refused import changes nothing. An invalid layout, a
     * session without an id, and two sessions with one id are each refused before the summary, layout or sessions
     * stored for the assembly are touched.
     */
    @Test
    void refusedAssemblySnapshotImportsLeaveStoredStateUnchanged() {
        StudioCatalogService service = new StudioCatalogService();
        StudioAssemblySnapshot before = seededAssembly(service);
        StudioDraftSession session = before.sessions().get(0);
        StudioAssemblySummary otherSummary = new StudioAssemblySummary("tenant-a", "assembly-checkout", "Replaced Name",
                "compose-local", "needs-other", "IN_PROCESS_LIBRARY", "cand-other", 9);
        StudioLayoutState invalidLayout = new StudioLayoutState("tenant-a", "assembly-checkout", null, "ASSEMBLY_DCP", "x",
                Map.of(), List.of(), "COCKPIT", List.of(), null, "TABLE", null);

        record Refusal(StudioAssemblySnapshot snapshot, String reason) {
        }
        List<Refusal> refusals = List.of(
                new Refusal(new StudioAssemblySnapshot("tenant-a", "assembly-checkout", otherSummary, invalidLayout,
                        List.of(), List.of()), "LAYOUT_STATE_INVALID: activeViewpoint"),
                new Refusal(new StudioAssemblySnapshot("tenant-a", "assembly-checkout", otherSummary, null,
                        List.of(withSessionId(session, " ")), List.of()), "ASSEMBLY_SNAPSHOT_INVALID: sessions[0].sessionId is required"),
                new Refusal(new StudioAssemblySnapshot("tenant-a", "assembly-checkout", otherSummary, null,
                        List.of(session, withSessionId(session, session.sessionId())), List.of()),
                        "ASSEMBLY_SNAPSHOT_INVALID: sessions[1].sessionId " + session.sessionId() + " appears twice"));
        for (Refusal refusal : refusals) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.loadAssemblySnapshot("tenant-a", "assembly-checkout", refusal.snapshot()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(refusal.reason());
            assertStoredAssembly(service, before);
        }
    }

    /**
     * Regression (LLD-fabric-studio-api, Assembly snapshots): when persisting a valid import fails, the previous
     * summary, layout and sessions are restored in memory and the failure is reported.
     */
    @Test
    void assemblySnapshotImportRollsBackWhenPersistingFails(@TempDir Path dir) throws Exception {
        Path statePath = dir.resolve("studio-state.json");
        StudioCatalogService service = new StudioCatalogService(new StudioStateStore(statePath));
        StudioAssemblySnapshot before = seededAssembly(service);
        StudioAssemblySnapshot replacement = new StudioAssemblySnapshot("tenant-a", "assembly-checkout",
                new StudioAssemblySummary("tenant-a", "assembly-checkout", "Replaced Name", "compose-local", "needs-other",
                        "IN_PROCESS_LIBRARY", "cand-other", 9),
                StudioLayoutState.initial("tenant-a", "assembly-checkout"), List.of(), List.of());
        // A non-empty directory where the state file was: the store's atomic replace cannot succeed.
        Files.delete(statePath);
        Files.createDirectories(statePath);
        Files.writeString(statePath.resolve("blocker"), "x");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.loadAssemblySnapshot("tenant-a", "assembly-checkout", replacement))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unable to write Studio state");
        assertStoredAssembly(service, before);
    }

    /** Fixture: an assembly with a saved summary, a workbench layout and one draft session; returns its snapshot. */
    private static StudioAssemblySnapshot seededAssembly(StudioCatalogService service) {
        StudioCatalogSnapshot catalog = service.saveCatalogSnapshot("tenant-a");
        service.saveDraft("tenant-a", "assembly-checkout", new StudioSaveDraftRequest(
                "Checkout Platform", "needs-checkout", "kubernetes-prod", "CONTAINERIZED_SERVICE", "cand-abc123", 3));
        service.saveLayout("tenant-a", "assembly-checkout", StudioLayoutStateRequest.of(
                "CHILD_DCP", "payment", Map.of(), List.of(), "TOPOLOGY", List.of("SECURITY"), "SECURITY", "TABLE", null));
        service.createDraftSession("tenant-a", "assembly-checkout", new StudioCreateDraftCompositionRequest(
                "tenant-a", "assembly-checkout", catalog.catalogHash(), "needs-checkout", "", "cand-abc123", "operator", "Operator"));
        StudioAssemblySnapshot snapshot = service.saveAssemblySnapshot("tenant-a", "assembly-checkout");
        assertThat(snapshot.sessions()).hasSize(1);
        return snapshot;
    }

    /** Asserts the assembly's stored summary, layout and sessions are exactly those of {@code expected}. */
    private static void assertStoredAssembly(StudioCatalogService service, StudioAssemblySnapshot expected) {
        StudioAssemblySnapshot now = service.saveAssemblySnapshot("tenant-a", "assembly-checkout");
        assertThat(now.assembly()).isEqualTo(expected.assembly());
        assertThat(now.layout()).isEqualTo(expected.layout());
        assertThat(now.sessions()).isEqualTo(expected.sessions());
    }

    /** The session with another id, everything else unchanged. */
    private static StudioDraftSession withSessionId(StudioDraftSession session, String sessionId) {
        return new StudioDraftSession(session.tenantId(), session.assemblyId(), sessionId, session.baseCatalogHash(),
                session.compositionMode(), session.needsId(), session.trustPolicyId(), session.currentCandidateId(),
                session.sceneRevision(), session.warnings(), session.collaborators(), session.intentLog(),
                session.catalogFileId(), session.displayName(), session.sessionType(), session.status(),
                session.createdAt(), session.updatedAt(), session.lastOpenedAt(), session.compileRecords());
    }

    @Test
    void rejectsAdmissionWhenDcpClaimValidationFails(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(jarDraft(dir, "broken.jar", "claim:\n" + indent("""
                        identity:
                          uri: urn:unfurl:test:broken
                          name: broken
                          kind: COMPONENT
                          version: 1.0.0
                          publisher: Unfurl
                        faults:
                          emitted: []
                        metadata:
                          dcp_version: 0.1.0
                          claim_version: 1.0.0
                        """, 2)))));

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
                .doesNotContain("uploaded:broken.jar");
    }

    /**
     * A draft that names a jar but sends no bytes has no claim to admit.
     */
    @Test
    void rejectsAdmissionWithoutArtifactBytes() {
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
                                assertThat(diagnostic.path()).isEqualTo("artifactBase64");
                                assertThat(diagnostic.message()).contains("artifactBase64 is required");
                            });
                });
    }

    /**
     * A claim document without the artifact that carries it is refused (DCP HLD-C §3.10), whether it comes as a .yaml
     * file or as claimYaml next to a jar.
     */
    @Test
    void refusesDetachedClaimDocuments(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        StudioComponentArtifactDraft jar = jarDraft(dir, "payment.jar", catalogManifestYaml("payment", "payment.process"));

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(
                        new StudioComponentArtifactDraft("payment.yaml", "", validClaimYaml("payment", "payment.process")),
                        new StudioComponentArtifactDraft("payment.jar", "", validClaimYaml("payment", "payment.process"),
                                jar.artifactBase64()))));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.results()).hasSize(2).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(result.diagnostics()).singleElement()
                    .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo("CLAIM_DETACHED"));
        });
        assertThat(response.catalog().entries())
                .extracting(StudioVisualCatalogEntry::catalogEntryId)
                .doesNotContain("uploaded:payment.yaml", "uploaded:payment.jar");
    }

    /**
     * Studio computes the artifact hash itself; a declared hash that does not match the uploaded bytes is refused.
     */
    @Test
    void refusesArtifactWhoseDeclaredHashDiffers(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        StudioComponentArtifactDraft jar = jarDraft(dir, "payment.jar", catalogManifestYaml("payment", "payment.process"));

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(new StudioComponentArtifactDraft("payment.jar", "sha256:" + "0".repeat(64), "",
                        jar.artifactBase64()))));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> assertThat(result.diagnostics()).singleElement()
                        .satisfies(diagnostic -> {
                            assertThat(diagnostic.code()).isEqualTo("ARTIFACT_HASH_MISMATCH");
                            assertThat(diagnostic.path()).isEqualTo("sha256");
                        }));
    }

    /**
     * A jar whose manifest carries a claim but no catalog block is refused; Studio never fills in catalog metadata.
     */
    @Test
    void refusesJarWhoseManifestHasNoCatalogBlock(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();

        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(jarDraft(dir, "payment.jar", "claim:\n" + indent(validClaimYaml("payment", "payment.process"), 2)))));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.results()).singleElement()
                .satisfies(result -> assertThat(result.diagnostics()).singleElement()
                        .satisfies(diagnostic -> {
                            assertThat(diagnostic.code()).isEqualTo("CLAIM_MALFORMED");
                            assertThat(diagnostic.path()).isEqualTo("artifact.META-INF/unfurl-catalog.yaml");
                            assertThat(diagnostic.message()).contains("catalog");
                        }));
    }

    /**
     * Verifies Studio admission can derive claim YAML from a JAR's embedded catalog
     * manifest, matching the UI upload path used for Foundry and substrate artifacts.
     */
    @Test
    void admitsJarArtifactByReadingEmbeddedCatalogManifest(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        byte[] jarBytes = jarWithManifest(dir, catalogManifestYaml("jar-payment", "payment.process"));
        String artifactBase64 = Base64.getEncoder().encodeToString(jarBytes);

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
        assertThat(response.catalog().entries().stream()
                .filter(entry -> "uploaded:payment.jar".equals(entry.catalogEntryId()))
                .findFirst()
                .orElseThrow()
                .artifactSha256())
                .isEqualTo("sha256:" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(jarBytes)));
    }

    /**
     * Verifies multi-file admission emits one downloadable ZIP that preserves each resolved
     * DCP claim as its own file with manifest and diagnostics metadata.
     */
    @Test
    void emitsDownloadableClaimBundleForMultiFileAdmission(@TempDir Path dir) throws Exception {
        StudioCatalogService service = new StudioCatalogService();
        StudioCatalogAdmissionResponse response = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(
                        jarDraft(dir, "payment-process.jar", catalogManifestYaml("payment", "payment.process")),
                        jarDraft(dir, "payment.jar", catalogManifestYaml("jar-payment", "payment.authorize")))));

        StudioExportArtifact bundle = response.claimBundleArtifact();
        assertThat(bundle).isNotNull();
        StudioAssetContent content = service.claimBundleContent("tenant-a", bundle.artifactId(), bundle.sha256())
                .orElseThrow();
        assertThat(content.mediaType()).isEqualTo("application/zip");
        assertThat(content.sha256()).isEqualTo(bundle.sha256());
        assertThat(zipEntries(content.bytes()))
                .contains(
                        "claims/01-payment-process-jar.claim.yaml",
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
    void extractsFlowfoundryNeedsFromSourceNames() {
        StudioCatalogService service = new StudioCatalogService();

        StudioNeedsExtractionResponse response = service.extractNeeds(
                "tenant-a",
                "flowfoundry-export",
                new StudioNeedsExtractionRequest(
                        "Flowfoundry Export",
                        List.of("workflow.yaml", "workload-agent.agent.yaml"),
                        "containerized-local"));

        Need parsed = new NeedsCodec().parse(response.suggestedNeedsYaml().getBytes(StandardCharsets.UTF_8));

        assertThat(response.needsId()).isEqualTo("flowfoundry-export-extracted-needs");
        assertThat(parsed.requiredCapabilities())
                .extracting(CapabilityRequirement::capability)
                .containsExactly("workflow.execute", "agent.run");
        assertThat(parsed.requiredCapabilities().get(1).requiredOfferDetails())
                .containsEntry("execution_modes", List.of("harness"));
        assertThat(response.suggestedNeedsYaml()).doesNotContain("flowfoundry.export.run");
        assertThat(response.defaultDeploymentTarget()).isEqualTo("containerized-local");
        assertThat(response.warnings()).containsExactly(
                "source file contents not supplied; inferred capabilities from file names only");
    }

    @Test
    void extractsWorkflowNodeNeedsFromInlineSource() {
        StudioCatalogService service = new StudioCatalogService();

        StudioNeedsExtractionResponse response = service.extractNeeds(
                "tenant-a",
                "flowfoundry-export",
                new StudioNeedsExtractionRequest(
                        "Flowfoundry Export",
                        List.of(),
                        "containerized-local",
                        List.of(new StudioNeedsExtractionSourceFile("workflow.yaml", """
                                id: export
                                nodes:
                                  - id: run-agent
                                    uses: agent.run
                                  - id: publish
                                    uses: storage.put
                                """))));

        Need parsed = new NeedsCodec().parse(response.suggestedNeedsYaml().getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.requiredCapabilities())
                .extracting(CapabilityRequirement::capability)
                .containsExactly("workflow.execute", "agent.run", "storage.put");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void fallsBackToStarterNeedsWhenNoDcpCapabilityCanBeInferred() {
        StudioCatalogService service = new StudioCatalogService();

        StudioNeedsExtractionResponse response = service.extractNeeds(
                "tenant-a",
                "assembly-checkout",
                new StudioNeedsExtractionRequest("Checkout Platform", List.of("README.md"), "kubernetes-prod"));

        Need parsed = new NeedsCodec().parse(response.suggestedNeedsYaml().getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.requiredCapabilities())
                .extracting(CapabilityRequirement::capability)
                .containsExactly("checkout.platform.run");
        assertThat(response.defaultDeploymentTarget()).isEqualTo("kubernetes-prod");
        assertThat(response.warnings()).contains(
                "source file contents not supplied; inferred capabilities from file names only",
                "no DCP capabilities could be inferred from supplied files; generated starter needs");
    }

    @Test
    void resolvesDeploymentFromTenantSessionInventory(@TempDir Path dir) throws Exception {
        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);

        StudioDeploymentResolveResponse response = service.resolveDeployment(new StudioDeploymentResolveRequest(
                null,
                null,
                null,
                null,
                true,
                containerPolicy(),
                "tenant-a",
                "assembly-flow",
                created.session().sessionId(),
                "",
                ""));

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.candidateId()).startsWith("cand-");
        assertThat(response.selections())
                .extracting(StudioDeploymentSelection::capability)
                .contains("workflow.execute", "agent.run");
        assertThat(response.selections())
                .extracting(selection -> selection.deploymentShape().name())
                .containsOnly("CONTAINERIZED_SERVICE");
    }

    /**
     * Regression test: uploaded manifest shape profiles must survive Studio state
     * persistence. Step 11 resolves deployment from a draft session after the
     * server has restarted, so visual-only reconstruction cannot flatten product
     * runtimes back to in-process library defaults.
     */
    @Test
    void resolvesDeploymentFromPersistedUploadedManifestShapeProfiles(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("studio-state.json"));
        StudioCatalogService first = flowfoundrySessionService(dir, store);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(first);
        addComponent(first, created.session(), "uploaded:flow.jar", 0);
        addComponent(first, created.session(), "uploaded:foundry.jar", 1);

        StudioCatalogService restarted = new StudioCatalogService(store, null);
        StudioDeploymentResolveResponse response = restarted.resolveDeployment(new StudioDeploymentResolveRequest(
                null,
                null,
                null,
                null,
                true,
                containerPolicy(),
                "tenant-a",
                "assembly-flow",
                created.session().sessionId(),
                "",
                ""));

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.selections())
                .extracting(StudioDeploymentSelection::capability)
                .contains("workflow.execute", "agent.run");
        assertThat(response.selections())
                .extracting(selection -> selection.deploymentShape().name())
                .containsOnly("CONTAINERIZED_SERVICE");
    }

    @Test
    void compilesFullSessionInventoryAndServesExportArtifacts(@TempDir Path dir) throws Exception {
        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);

        StudioCompileDraftCandidateResponse response = service.compileCandidate(
                "tenant-a",
                "assembly-flow",
                created.session().sessionId(),
                new StudioCompileDraftCandidateRequest(
                        "tenant-a",
                        "assembly-flow",
                        created.session().sessionId(),
                        2,
                        false,
                        containerPolicy()));

        assertThat(response.status())
                .as(response.reason() + ": " + response.details())
                .isEqualTo("COMPILED");
        assertThat(response.candidateId()).startsWith("cand-");
        assertThat(response.candidateId()).isNotEqualTo("uploaded:foundry.jar");
        assertThat(response.expectedRevision()).isEqualTo(2);
        assertThat(response.receivedRevision()).isEqualTo(2);
        assertThat(response.contractArtifact().url()).contains("/studio/tenants/tenant-a/exports/");
        assertThat(service.exportArtifactContent(
                "tenant-a",
                response.contractArtifact().artifactId(),
                response.contractArtifact().sha256()))
                .hasValueSatisfying(content -> {
                    String contract = new String(content.bytes(), StandardCharsets.UTF_8);
                    assertThat(contract)
                            .contains("providerCapability: assembly.aggregate")
                            .contains("childContractCount: 2")
                            .doesNotContain("childContracts")
                            .doesNotContain("bindingPlan")
                            .doesNotContain("selections");
                });
        StudioExportArtifact compiledEnvelope = response.diagnosticArtifacts().stream()
                .filter(artifact -> artifact.url().contains("compiled-contract-envelope.yaml"))
                .findFirst()
                .orElseThrow();
        assertThat(service.diagnosticArtifactContent(
                "tenant-a",
                compiledEnvelope.artifactId(),
                compiledEnvelope.sha256()))
                .hasValueSatisfying(content -> {
                    String envelope = new String(content.bytes(), StandardCharsets.UTF_8);
                    assertThat(envelope)
                            .contains("com.unfurl:flow:1.0.0")
                            .contains("com.unfurl:foundry:1.0.0")
                            .contains("bindingPlan")
                            .contains("selections");
                });
        assertThat(response.diagnosticArtifacts())
                .extracting(StudioExportArtifact::url)
                .anyMatch(url -> url.contains("compiled-contract-envelope.yaml"))
                .anyMatch(url -> url.contains("compile-response.json"));
        assertThat(service.exportArtifactContent(
                "tenant-a",
                response.substrateProfileArtifact().artifactId(),
                response.substrateProfileArtifact().sha256()))
                .hasValueSatisfying(content -> assertThat(content.mediaType()).isEqualTo("application/yaml"));
        assertThat(service.exportArtifactContent(
                "tenant-a",
                response.contractArtifact().artifactId(),
                "sha256:wrong"))
                .isEmpty();
    }

    /**
     * After a restart, compile rebuilds each admitted component from the manifest text Studio kept from its jar, so the
     * compiled claim is still the one the jar carries.
     */
    @Test
    void compilesAfterRestartFromThePersistedManifest(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("studio-state.json"));
        StudioCatalogService first = flowfoundrySessionService(dir, store);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(first);
        addComponent(first, created.session(), "uploaded:flow.jar", 0);
        addComponent(first, created.session(), "uploaded:foundry.jar", 1);

        StudioCompileDraftCandidateResponse response = compile(new StudioCatalogService(store, null), created.session());

        assertThat(response.status()).as(response.reason() + ": " + response.details()).isEqualTo("COMPILED");
    }

    /**
     * Studio companion admission (HLD-in-process-component-hosting §3, §10): a hosted component compiles only once its
     * companion is admitted; the companion is stored, persisted and pinned, a different jar under the same coordinates
     * is refused naming the stored one, and the compile response audits the host edge and the companion's hash.
     */
    @Test
    void admitsCompanionJarsAndPinsThemIntoHostedComponents(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("state").resolve("studio-state.json"));
        StudioCatalogService service = new StudioCatalogService(store, null);
        assertThat(service.admit("tenant-a", new StudioCatalogAdmissionRequest("assembly-flow", List.of(
                jarDraft(dir, "flow.jar", catalogManifestYaml("flow", "workflow.execute")),
                jarDraft(dir, "foundry.jar", hostManifestYaml("foundry", "agent.run")),
                jarDraft(dir, "broker.jar", hostedManifestYaml("broker", "broker.read", COMPANION))))).status())
                .isEqualTo("VERIFIED");
        int catalogSize = service.listCatalogVisuals("tenant-a").entries().size();
        service.extractNeeds("tenant-a", "assembly-flow", new StudioNeedsExtractionRequest("Flowfoundry Export",
                List.of("workflow.yaml", "workload.agent.yaml"), "containerized-local"));
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);
        addComponent(service, created.session(), "uploaded:broker.jar", 2);

        StudioCompileDraftCandidateResponse missing = compile(service, created.session());
        assertThat(missing.status()).isEqualTo("INVALID");
        assertThat(missing.reason()).isEqualTo("CATALOG_COMPANION_MISSING");
        assertThat(missing.details()).contains(COMPANION);

        byte[] companion = companionJar(dir, "broker-http.jar", new byte[]{1});
        StudioCatalogAdmissionResponse admitted = service.admit("tenant-a", new StudioCatalogAdmissionRequest("assembly-flow",
                List.of(new StudioComponentArtifactDraft("broker-http.jar", "", "", Base64.getEncoder().encodeToString(companion)))));
        assertThat(admitted.status()).isEqualTo("VERIFIED");
        assertThat(admitted.results()).singleElement()
                .satisfies(result -> assertThat(result.catalogEntryId()).isEqualTo("companion:" + COMPANION));
        assertThat(admitted.catalog().entries()).hasSize(catalogSize);
        String companionSha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(companion));

        StudioCatalogAdmissionResponse conflict = service.admit("tenant-a", new StudioCatalogAdmissionRequest("assembly-flow",
                List.of(new StudioComponentArtifactDraft("broker-http-rebuilt.jar", "", "",
                        Base64.getEncoder().encodeToString(companionJar(dir, "rebuilt.jar", new byte[]{2}))))));
        assertThat(conflict.status()).isEqualTo("REJECTED");
        assertThat(conflict.results()).singleElement().satisfies(result -> assertThat(result.diagnostics()).singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("CATALOG_COMPANION_CONFLICT");
                    assertThat(diagnostic.message()).contains("broker-http.jar (sha256 " + companionSha + ")", "broker-http-rebuilt.jar");
                }));

        // After a restart the persisted companion still resolves, and the audit shows the host edge and its hash.
        StudioCatalogService restarted = new StudioCatalogService(store, null);
        StudioCompileDraftCandidateResponse compiled = compile(restarted, created.session());
        assertThat(compiled.status()).as(compiled.reason() + ": " + compiled.details()).isEqualTo("COMPILED");
        StudioExportArtifact audit = compiled.diagnosticArtifacts().stream()
                .filter(artifact -> artifact.url().contains("hosted-components.json")).findFirst().orElseThrow();
        assertThat(restarted.diagnosticArtifactContent("tenant-a", audit.artifactId(), audit.sha256()))
                .hasValueSatisfying(content -> assertThat(new String(content.bytes(), StandardCharsets.UTF_8))
                        .contains("\"component\" : \"com.unfurl:broker:1.0.0\"", "\"host\" : \"com.unfurl:foundry:1.0.0\"",
                                "\"coordinates\" : \"" + COMPANION + "\"", "\"sha256\" : \"" + companionSha + "\""));
    }

    // ---- P2a: compile records, contract diff, verification, typed NO_MATCH reasons (LLD-fabric-studio-api) ----

    /**
     * Compile records: one per successful compile, none for a stale or refused one; the response carries the record;
     * records and the bytes they name survive a restart.
     */
    @Test
    void recordsEachSuccessfulCompileAndKeepsItAcrossARestart(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("state").resolve("studio-state.json"));
        StudioCatalogService service = flowfoundrySessionService(dir, store);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        String sessionId = created.session().sessionId();
        addComponent(service, created.session(), "uploaded:flow.jar", 0);

        StudioCompileDraftCandidateResponse refused = compile(service, created.session());
        assertThat(refused.status()).isEqualTo("INVALID");
        assertThat(refused.compileRecord()).isNull();
        StudioCompileDraftCandidateResponse stale = service.compileCandidate("tenant-a", "assembly-flow", sessionId,
                new StudioCompileDraftCandidateRequest("tenant-a", "assembly-flow", sessionId, 0, false, containerPolicy()));
        assertThat(stale.status()).isEqualTo("STALE_REVISION");
        assertThat(service.draftSession("tenant-a", "assembly-flow", sessionId).compileRecords()).isEmpty();

        addComponent(service, created.session(), "uploaded:foundry.jar", 1);
        StudioCompileDraftCandidateResponse compiled = compile(service, created.session());
        assertThat(compiled.status()).as(compiled.reason() + ": " + compiled.details()).isEqualTo("COMPILED");
        StudioCompileRecord record = compiled.compileRecord();
        assertThat(record.revision()).isEqualTo(2);
        assertThat(record.signed()).isFalse();
        assertThat(service.draftSession("tenant-a", "assembly-flow", sessionId).compileRecords()).containsExactly(record);
        assertThat(dir.resolve("state").resolve(StudioCompiledContractStore.FOLDER)).isDirectory();

        StudioCatalogService restarted = new StudioCatalogService(store, null);
        assertThat(restarted.draftSession("tenant-a", "assembly-flow", sessionId).compileRecords()).containsExactly(record);
        StudioContractDiff diff = restarted.contractDiff("tenant-a", "assembly-flow", sessionId, 2, null,
                Optional.of(containerPolicy()));
        assertThat(diff.available()).as(String.valueOf(diff.absentReason())).isTrue();
        assertThat(diff.diff().canonicalHashChanged()).isFalse();
        assertThat(diff.diff().ports()).isNotNull();
        assertThat(diff.diagnosticArtifacts()).singleElement()
                .satisfies(artifact -> assertThat(artifact.url()).contains("contract-diff.json"));
    }

    /**
     * Contract diff: COMPILE_REQUIRED before any compile; current, historical, stale by revision and by policy; a revision
     * with no record; and a damaged store file, which the hash check refuses.
     */
    @Test
    void diffsCompileRecordsUnderTheFreshnessRule(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("state").resolve("studio-state.json"));
        StudioCatalogService service = flowfoundrySessionService(dir, store);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        String sessionId = created.session().sessionId();
        assertThat(service.contractDiff("tenant-a", "assembly-flow", sessionId, 0, null, Optional.empty()).absentReason())
                .satisfies(reason -> assertThat(reason.code()).isEqualTo("COMPILE_REQUIRED"));

        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);
        StudioCompileRecord containerized = compile(service, created.session()).compileRecord();
        // Move the revision without changing the composition, then compile it in process.
        removeComponent(service, created.session(), "uploaded:foundry.jar", 2);
        addComponent(service, created.session(), "uploaded:foundry.jar", 3);
        StudioCompileDraftCandidateResponse inProcess = service.compileCandidate("tenant-a", "assembly-flow", sessionId,
                new StudioCompileDraftCandidateRequest("tenant-a", "assembly-flow", sessionId, 4, false, inProcessPolicy()));
        assertThat(inProcess.status()).as(inProcess.reason() + ": " + inProcess.details()).isEqualTo("COMPILED");
        assertThat(inProcess.compileRecord().deploymentPolicyHash()).isNotEqualTo(containerized.deploymentPolicyHash());

        StudioContractDiff current = service.contractDiff("tenant-a", "assembly-flow", sessionId, 2, null,
                Optional.of(inProcessPolicy()));
        assertThat(current.available()).as(String.valueOf(current.absentReason())).isTrue();
        assertThat(current.historical()).isFalse();
        assertThat(current.base().sourceRevision()).isEqualTo(2);
        assertThat(current.target().sourceRevision()).isEqualTo(4);
        assertThat(current.diff().changedDeploymentShapes())
                .isNotEmpty()
                .allSatisfy(change -> {
                    assertThat(change.fromShape()).isEqualTo("CONTAINERIZED_SERVICE");
                    assertThat(change.toShape()).isEqualTo("IN_PROCESS_LIBRARY");
                });

        StudioContractDiff otherPolicy = service.contractDiff("tenant-a", "assembly-flow", sessionId, 2, null,
                Optional.of(containerPolicy()));
        assertThat(otherPolicy.available()).isFalse();
        assertThat(otherPolicy.diff()).isNull();
        assertThat(otherPolicy.absentReason().code()).isEqualTo("COMPILE_STALE");
        assertThat(otherPolicy.absentReason().message()).contains("different deployment policy");

        StudioContractDiff historical = service.contractDiff("tenant-a", "assembly-flow", sessionId, 2, 2L,
                Optional.of(inProcessPolicy()));
        assertThat(historical.available()).isTrue();
        assertThat(historical.historical()).isTrue();
        assertThat(historical.diff().canonicalHashChanged()).isFalse();

        assertThat(service.contractDiff("tenant-a", "assembly-flow", sessionId, 3, null, Optional.empty()).absentReason())
                .satisfies(reason -> {
                    assertThat(reason.code()).isEqualTo("COMPILE_RECORD_UNAVAILABLE");
                    assertThat(reason.message()).contains("revision 3");
                });

        removeComponent(service, created.session(), "uploaded:foundry.jar", 4);
        StudioContractDiff moved = service.contractDiff("tenant-a", "assembly-flow", sessionId, 2, null, Optional.empty());
        assertThat(moved.absentReason().code()).isEqualTo("COMPILE_STALE");
        assertThat(moved.absentReason().message()).isEqualTo("compiled at revision 4; the draft is at revision 5");

        // A damaged file no longer hashes to its name, so it is never used.
        try (var files = Files.walk(dir.resolve("state").resolve(StudioCompiledContractStore.FOLDER))) {
            Path envelope = files.filter(path -> path.getFileName().toString().equals(containerized.compiledEnvelopeSha256()))
                    .findFirst().orElseThrow();
            Files.writeString(envelope, Files.readString(envelope) + "\n# edited");
        }
        // The record at revision 2 is a historical target, read without the freshness rule, so its damaged envelope is
        // what the answer names.
        StudioContractDiff damaged = service.contractDiff("tenant-a", "assembly-flow", sessionId, 4, 2L, Optional.empty());
        assertThat(damaged.absentReason().code()).isEqualTo("COMPILE_RECORD_UNAVAILABLE");
        assertThat(damaged.absentReason().message()).contains(containerized.compiledEnvelopeSha256());
    }

    /**
     * Verification: an unsigned compile is NOT_SIGNED; a signed one without a trust-key directory is
     * TRUST_KEYS_UNAVAILABLE; with the signer's key trusted it verifies against a clean catalog; with only another key
     * trusted the signature is KeyNotPinned; a past record by contract hash is historical.
     */
    @Test
    void verifiesASignedCompileOnlyWithConfiguredTrustKeys(@TempDir Path dir) throws Exception {
        KeyPair keys = generateStudioSigningKeyPair();
        Path privateKey = writePrivateKeyPem(dir, "studio-private.pem", keys);
        Path publicKey = writePublicKeyPem(dir, "studio-public.pem", keys);
        Path trusted = Files.createDirectories(dir.resolve("trusted"));
        writePublicKeyPem(trusted, "studio.pem", keys);
        Path untrusted = Files.createDirectories(dir.resolve("untrusted"));
        writePublicKeyPem(untrusted, "other.pem", generateStudioSigningKeyPair());
        String previousPrivateKey = System.getProperty("unfurl.studio.signing.privateKey");
        String previousPublicKey = System.getProperty("unfurl.studio.signing.publicKey");
        String previousKeysDir = System.getProperty("unfurl.studio.trust.keysDir");
        try {
            System.setProperty("unfurl.studio.signing.privateKey", privateKey.toString());
            System.setProperty("unfurl.studio.signing.publicKey", publicKey.toString());
            System.clearProperty("unfurl.studio.trust.keysDir");
            StudioCatalogService service = flowfoundrySessionService(dir);
            StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
            String sessionId = created.session().sessionId();
            addComponent(service, created.session(), "uploaded:flow.jar", 0);
            addComponent(service, created.session(), "uploaded:foundry.jar", 1);

            StudioCompileRecord unsigned = compile(service, created.session()).compileRecord();
            assertThat(service.verifyCompile("tenant-a", "assembly-flow", sessionId, null).absentReason().code())
                    .isEqualTo("NOT_SIGNED");

            StudioCompileDraftCandidateResponse signedCompile = service.compileCandidate("tenant-a", "assembly-flow",
                    sessionId, new StudioCompileDraftCandidateRequest("tenant-a", "assembly-flow", sessionId, 2, true,
                            containerPolicy()));
            assertThat(signedCompile.compileRecord().signed()).isTrue();
            StudioVerifyRequest current = new StudioVerifyRequest(null, null, containerPolicy());
            assertThat(service.verifyCompile("tenant-a", "assembly-flow", sessionId, current).absentReason().code())
                    .isEqualTo("TRUST_KEYS_UNAVAILABLE");

            System.setProperty("unfurl.studio.trust.keysDir", trusted.toString());
            StudioVerificationResult verified = service.verifyCompile("tenant-a", "assembly-flow", sessionId, current);
            assertThat(verified.available()).as(String.valueOf(verified.absentReason())).isTrue();
            assertThat(verified.historical()).isFalse();
            assertThat(verified.signature().ok()).isTrue();
            assertThat(verified.signature().result()).isEqualTo("Ok");
            assertThat(verified.catalogDrift().clean()).isTrue();
            assertThat(verified.overallOk()).isTrue();
            assertThat(verified.provenance().contractHash()).isEqualTo(signedCompile.compileRecord().contractHash());

            System.setProperty("unfurl.studio.trust.keysDir", untrusted.toString());
            StudioVerificationResult notPinned = service.verifyCompile("tenant-a", "assembly-flow", sessionId, current);
            assertThat(notPinned.signature().result()).isEqualTo("KeyNotPinned");
            assertThat(notPinned.overallOk()).isFalse();

            StudioVerificationResult past = service.verifyCompile("tenant-a", "assembly-flow", sessionId,
                    new StudioVerifyRequest(null, unsigned.contractHash(), null));
            // Each compile's audit carries its own compiledAt, so the earlier unsigned compile has its own hash: it is
            // historical, and still not signed.
            assertThat(past.historical()).isTrue();
            assertThat(past.provenance().contractHash()).isEqualTo(unsigned.contractHash());
            assertThat(past.absentReason().code()).isEqualTo("NOT_SIGNED");
        } finally {
            restoreProperty("unfurl.studio.signing.privateKey", previousPrivateKey);
            restoreProperty("unfurl.studio.signing.publicKey", previousPublicKey);
            restoreProperty("unfurl.studio.trust.keysDir", previousKeysDir);
        }
    }

    // ---- P2d: the emit-plan preview (LLD-fabric-studio-api, StudioEmitPlanPreview) ----

    /**
     * The emit-plan preview refuses in order, under each step's code (unknown environment, no compile, unsigned, no trust
     * keys); with a signed compile and trusted keys it runs the real emit and reports the artifacts, the secret requests
     * by name and the generated bindings on their draft nodes; it answers again from its cache, answers anew after an
     * environment save, refuses identity inputs for a composition without identity (rule 6, the path without identity in
     * the contract), is COMPILE_STALE after an edit, and leaves no temporary folder behind.
     */
    @Test
    void previewsTheEmitPlanOfTheCurrentSignedCompile(@TempDir Path dir) throws Exception {
        KeyPair keys = generateStudioSigningKeyPair();
        Path privateKey = writePrivateKeyPem(dir, "studio-private.pem", keys);
        Path publicKey = writePublicKeyPem(dir, "studio-public.pem", keys);
        Path trusted = Files.createDirectories(dir.resolve("trusted"));
        writePublicKeyPem(trusted, "studio.pem", keys);
        String previousPrivateKey = System.getProperty("unfurl.studio.signing.privateKey");
        String previousPublicKey = System.getProperty("unfurl.studio.signing.publicKey");
        String previousKeysDir = System.getProperty("unfurl.studio.trust.keysDir");
        long foldersBefore = emitPlanFolders();
        try {
            System.setProperty("unfurl.studio.signing.privateKey", privateKey.toString());
            System.setProperty("unfurl.studio.signing.publicKey", publicKey.toString());
            System.clearProperty("unfurl.studio.trust.keysDir");
            StudioCatalogService service = flowfoundrySessionService(dir);
            // The real compose backend: this test classpath also registers a stand-in for local-compose.
            service.useEmitters(new com.unfurl.deploy.core.EmitterBootstrap(
                    List.of(new com.unfurl.deploy.compose.ComposeEmitterProvider())));
            StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
            String sessionId = created.session().sessionId();
            addComponent(service, created.session(), "uploaded:flow.jar", 0);
            addComponent(service, created.session(), "uploaded:foundry.jar", 1);
            Optional<StudioDeploymentPolicyDraft> policy = Optional.of(containerPolicy());

            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy).absentReason().code())
                    .isEqualTo("ENVIRONMENT_UNKNOWN");
            service.saveDeploymentEnvironment("tenant-a", "local", localEnvironment(0, null, null));
            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy).absentReason().code())
                    .isEqualTo("COMPILE_REQUIRED");
            compile(service, created.session());
            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy).absentReason().code())
                    .isEqualTo("NOT_SIGNED");
            StudioCompileRecord signed = service.compileCandidate("tenant-a", "assembly-flow", sessionId,
                    new StudioCompileDraftCandidateRequest("tenant-a", "assembly-flow", sessionId, 2, true,
                            containerPolicy())).compileRecord();
            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy).absentReason().code())
                    .isEqualTo("TRUST_KEYS_UNAVAILABLE");

            System.setProperty("unfurl.studio.trust.keysDir", trusted.toString());
            StudioEmitPlanPreview plan = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(plan.available()).as(String.valueOf(plan.absentReason())).isTrue();
            assertThat(plan.targetKind()).isEqualTo("local-compose");
            assertThat(plan.provenance().contractHash()).isEqualTo(signed.contractHash());
            assertThat(plan.environmentRevision()).isEqualTo(1L);
            assertThat(plan.artifacts()).isNotEmpty().allSatisfy(artifact -> assertThat(artifact.sha256()).hasSize(64));
            assertThat(plan.bindingsSha256()).hasSize(64);
            assertThat(plan.emitManifestSha256()).isNotBlank();
            assertThat(plan.runtimeBindings()).filteredOn(binding -> binding.capability() != null)
                    .extracting(StudioEmitPlanPreview.Binding::capability)
                    .containsExactlyInAnyOrder("workflow.execute", "agent.run");
            assertThat(plan.runtimeBindings()).filteredOn(binding -> binding.capability() != null).allSatisfy(binding -> {
                assertThat(binding.providerNodeId()).isNotBlank();
                assertThat(binding.timeoutMs()).isEqualTo(30000);
                assertThat(binding.auditEnabled()).isTrue();
                assertThat(binding.enabled()).isTrue();
            });
            assertThat(plan.requiredSecrets()).allSatisfy(secret -> assertThat(secret.name()).doesNotContain("="));
            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy))
                    .as("asked again, the cached answer").isSameAs(plan);

            service.saveDeploymentEnvironment("tenant-a", "local", localEnvironment(1, 45000, null));
            StudioEmitPlanPreview saved = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(saved.environmentRevision()).isEqualTo(2L);
            assertThat(saved.runtimeBindings()).filteredOn(binding -> binding.capability() != null)
                    .allSatisfy(binding -> assertThat(binding.timeoutMs()).isEqualTo(45000));

            // Deleted and created again, the environment is at revision 1 again with other settings: the cached answer
            // for the first revision 1 is not served (the key is the content, not the revision).
            service.deleteDeploymentEnvironment("tenant-a", "local", 2);
            service.saveDeploymentEnvironment("tenant-a", "local", localEnvironment(0, 50000, null));
            StudioEmitPlanPreview recreated = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(recreated.environmentRevision()).isEqualTo(1L);
            assertThat(recreated.environmentSha256()).isNotEqualTo(plan.environmentSha256()).hasSize(64);
            assertThat(recreated.runtimeBindings()).filteredOn(binding -> binding.capability() != null)
                    .allSatisfy(binding -> assertThat(binding.timeoutMs()).isEqualTo(50000));

            service.saveDeploymentEnvironment("tenant-a", "local", localEnvironment(1, null, new StudioDeploymentEnvironment.Identity(
                    "https://idp.example/realms/acme", Map.of("urn:unfurl:test:foundry", "foundry"), "tenant", "roles",
                    "permissions", "config://idp/token-endpoint", "config://idp/keys", "secret://idp/client", Map.of())));
            StudioEmitPlanPreview stray = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(stray.absentReason().code()).isEqualTo("IDENTITY_INPUTS_WITHOUT_IDENTITY");
            assertThat(stray.runtimeBindings()).isEmpty();
            assertThat(stray.provenance().contractHash()).isEqualTo(signed.contractHash());

            removeComponent(service, created.session(), "uploaded:foundry.jar", 2);
            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy).absentReason().code())
                    .isEqualTo("COMPILE_STALE");
            assertThat(emitPlanFolders()).as("every run's folder is deleted").isEqualTo(foldersBefore);
        } finally {
            restoreProperty("unfurl.studio.signing.privateKey", previousPrivateKey);
            restoreProperty("unfurl.studio.signing.publicKey", previousPublicKey);
            restoreProperty("unfurl.studio.trust.keysDir", previousKeysDir);
        }
    }

    /**
     * A refusal that comes from an input outside Studio state is never cached: with only another key trusted the
     * pipeline's preflight refuses, and once the operator puts the signer's key in the same folder the next preview is
     * available. An available answer is reused, and replaced as soon as a file in the trust-key folder changes
     * (LLD-fabric-studio-api, StudioEmitPlanPreview, Cache).
     */
    @Test
    void neverServesAFailureOrAnAnswerForOutsideInputsThatChanged(@TempDir Path dir) throws Exception {
        KeyPair keys = generateStudioSigningKeyPair();
        Path privateKey = writePrivateKeyPem(dir, "studio-private.pem", keys);
        Path publicKey = writePublicKeyPem(dir, "studio-public.pem", keys);
        Path trust = Files.createDirectories(dir.resolve("trust"));
        writePublicKeyPem(trust, "other.pem", generateStudioSigningKeyPair());
        String previousPrivateKey = System.getProperty("unfurl.studio.signing.privateKey");
        String previousPublicKey = System.getProperty("unfurl.studio.signing.publicKey");
        String previousKeysDir = System.getProperty("unfurl.studio.trust.keysDir");
        try {
            System.setProperty("unfurl.studio.signing.privateKey", privateKey.toString());
            System.setProperty("unfurl.studio.signing.publicKey", publicKey.toString());
            System.setProperty("unfurl.studio.trust.keysDir", trust.toString());
            StudioCatalogService service = flowfoundrySessionService(dir);
            service.useEmitters(new com.unfurl.deploy.core.EmitterBootstrap(
                    List.of(new com.unfurl.deploy.compose.ComposeEmitterProvider())));
            StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
            String sessionId = created.session().sessionId();
            addComponent(service, created.session(), "uploaded:flow.jar", 0);
            addComponent(service, created.session(), "uploaded:foundry.jar", 1);
            service.saveDeploymentEnvironment("tenant-a", "local", localEnvironment(0, null, null));
            service.compileCandidate("tenant-a", "assembly-flow", sessionId, new StudioCompileDraftCandidateRequest(
                    "tenant-a", "assembly-flow", sessionId, 2, true, containerPolicy()));
            Optional<StudioDeploymentPolicyDraft> policy = Optional.of(containerPolicy());

            StudioEmitPlanPreview untrusted = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(untrusted.available()).isFalse();
            assertThat(untrusted.absentReason().code()).isEqualTo("EMIT_PREFLIGHT_FAILED");

            writePublicKeyPem(trust, "studio.pem", keys);
            StudioEmitPlanPreview repaired = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(repaired.available()).as("the repair shows at once: " + repaired.absentReason()).isTrue();
            assertThat(service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy)).isSameAs(repaired);

            Files.delete(trust.resolve("other.pem"));
            StudioEmitPlanPreview changed = service.emitPlan("tenant-a", "assembly-flow", sessionId, "local", policy);
            assertThat(changed.available()).isTrue();
            assertThat(changed).as("a changed trust-key folder renders again").isNotSameAs(repaired);
        } finally {
            restoreProperty("unfurl.studio.signing.privateKey", previousPrivateKey);
            restoreProperty("unfurl.studio.signing.publicKey", previousPublicKey);
            restoreProperty("unfurl.studio.trust.keysDir", previousKeysDir);
        }
    }

    /**
     * Admission keeps each admitted component jar's bytes in the tenant's content-addressed artifact store, under the
     * SHA-256 the catalog entry pins (LLD-fabric-studio-api, Tenant Catalog and Assets [P2d]).
     */
    @Test
    void admissionKeepsTheAdmittedJar(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("state").resolve("studio-state.json"));
        StudioCatalogService service = flowfoundrySessionService(dir, store);
        String sha = service.listCatalogVisuals("tenant-a").entries().stream()
                .filter(entry -> entry.catalogEntryId().equals("uploaded:flow.jar")).findFirst().orElseThrow()
                .artifactSha256().substring("sha256:".length());
        try (var files = Files.walk(dir.resolve("state").resolve("studio-companions"))) {
            Path stored = files.filter(path -> path.getFileName().toString().equals(sha + ".jar")).findFirst().orElseThrow();
            assertThat(StudioCompanionStore.sha256(Files.readAllBytes(stored))).isEqualTo(sha);
        }
    }

    /**
     * An environment stored before the reserved-option rule can still name a generated input; the preview refuses it
     * with ENVIRONMENT_INVALID naming the option before anything renders, so it never reports bindings the emitter would
     * not read (LLD-fabric-studio-api, Deployment Environments, Reserved options).
     */
    @Test
    void refusesAPreviewOfAStoredEnvironmentNamingAGeneratedInput(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("state").resolve("studio-state.json"));
        StudioDeploymentEnvironment legacy = new StudioDeploymentEnvironment("local", 1, java.time.Instant.EPOCH,
                new StudioDeploymentEnvironment.Target("local-compose", "local", null, null,
                        Map.of("runtimeBindingPath", "/operator/bindings.yaml"), List.of()),
                new StudioDeploymentEnvironment.Bindings(Map.of(), null, null, null, List.of(), null, List.of()));
        store.save(new StudioStateStore.State(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("tenant-a", Map.of("local", legacy))));
        StudioCatalogService service = flowfoundrySessionService(dir, store);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);

        StudioEmitPlanPreview plan = service.emitPlan("tenant-a", "assembly-flow", created.session().sessionId(), "local",
                Optional.of(containerPolicy()));

        assertThat(plan.absentReason().code()).isEqualTo("ENVIRONMENT_INVALID");
        assertThat(plan.absentReason().message()).contains("target.options.runtimeBindingPath");
        assertThat(plan.runtimeBindings()).isEmpty();
    }

    /** Deployment environments persist with Studio state and come back after a restart. */
    @Test
    void deploymentEnvironmentsPersistAcrossRestarts(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("state").resolve("studio-state.json"));
        StudioCatalogService service = new StudioCatalogService(store, null);
        StudioDeploymentEnvironment saved = service.saveDeploymentEnvironment("tenant-a", "local", localEnvironment(0, 45000, null));

        StudioCatalogService restarted = new StudioCatalogService(store, null);
        assertThat(restarted.deploymentEnvironments("tenant-a")).containsExactly(saved);
        assertThat(restarted.deploymentEnvironments("tenant-b")).isEmpty();
    }

    /** A local-compose environment for the flow/foundry fixtures, at the expected revision. */
    private static StudioDeploymentEnvironmentRequest localEnvironment(long expectedRevision, Integer timeoutMs,
                                                                       StudioDeploymentEnvironment.Identity identity) {
        return new StudioDeploymentEnvironmentRequest(expectedRevision,
                new StudioDeploymentEnvironment.Target("local-compose", "local", null, null, Map.of(), List.of()),
                new StudioDeploymentEnvironment.Bindings(Map.of(
                        "urn:unfurl:test:flow", "http://flow:8080", "urn:unfurl:test:foundry", "http://foundry:7979"),
                        timeoutMs, null, identity, List.of(), null, List.of()));
    }

    /** How many emit-plan folders are in the temporary directory now. */
    private static long emitPlanFolders() throws IOException {
        try (var entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.filter(path -> path.getFileName().toString().startsWith("studio-emit-plan-")).count();
        }
    }

    /**
     * A session NO_MATCH keeps its details text and carries the same causes typed, built by the same presenter; a
     * session applies no trust classification, so it carries no trust rejections.
     */
    @Test
    void reportsASessionNoMatchWithTypedReasons(@TempDir Path dir) throws Exception {
        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        addComponent(service, created.session(), "uploaded:flow.jar", 0);

        StudioCompileDraftCandidateResponse response = compile(service, created.session());

        assertThat(response.reason()).isEqualTo("NO_MATCH");
        assertThat(response.reasons()).isNotEmpty()
                .allSatisfy(reason -> assertThat(reason.kind()).isEqualTo(MatchFailureReason.Kind.CONFLICT));
        assertThat(response.reasons()).anySatisfy(reason -> {
            assertThat(reason.reasonCode()).isEqualTo("MISSING_CAPABILITY");
            assertThat(reason.capability()).isEqualTo("agent.run");
        });
        assertThat(response.details()).isEqualTo(String.join("; ",
                response.reasons().stream().map(MatchFailureReason::message).toList()));
        assertThat(response.trustRejections()).isEmpty();

        StudioDeploymentResolveResponse resolve = service.resolveDeployment(new StudioDeploymentResolveRequest(
                null, null, null, null, true, containerPolicy(), "tenant-a", "assembly-flow",
                created.session().sessionId(), "", ""));
        assertThat(resolve.reason()).isEqualTo("NO_MATCH");
        assertThat(resolve.reasons()).isEqualTo(response.reasons());
    }

    /**
     * A record is proved against its bytes before anything is verified or diffed: a record whose contract hash was
     * rewritten in the state file, or whose signed envelope belongs to another compile, is COMPILE_RECORD_INCONSISTENT,
     * never verified for the hash it claims (LLD-fabric-studio-api, A record is checked against its bytes).
     */
    @Test
    void refusesARecordWhoseBytesDoNotBelongToIt(@TempDir Path dir) throws Exception {
        KeyPair keys = generateStudioSigningKeyPair();
        Path privateKey = writePrivateKeyPem(dir, "studio-private.pem", keys);
        Path publicKey = writePublicKeyPem(dir, "studio-public.pem", keys);
        Path trusted = Files.createDirectories(dir.resolve("trusted"));
        writePublicKeyPem(trusted, "studio.pem", keys);
        String previousPrivateKey = System.getProperty("unfurl.studio.signing.privateKey");
        String previousPublicKey = System.getProperty("unfurl.studio.signing.publicKey");
        String previousKeysDir = System.getProperty("unfurl.studio.trust.keysDir");
        try {
            System.setProperty("unfurl.studio.signing.privateKey", privateKey.toString());
            System.setProperty("unfurl.studio.signing.publicKey", publicKey.toString());
            System.setProperty("unfurl.studio.trust.keysDir", trusted.toString());
            Path statePath = dir.resolve("state").resolve("studio-state.json");
            StudioStateStore store = new StudioStateStore(statePath);
            StudioCatalogService service = flowfoundrySessionService(dir, store);
            StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
            String sessionId = created.session().sessionId();
            addComponent(service, created.session(), "uploaded:flow.jar", 0);
            addComponent(service, created.session(), "uploaded:foundry.jar", 1);
            StudioCompileRecord first = signedCompile(service, sessionId, 2);
            StudioCompileRecord second = signedCompile(service, sessionId, 2);
            StudioVerifyRequest current = new StudioVerifyRequest(null, null, containerPolicy());
            assertThat(service.verifyCompile("tenant-a", "assembly-flow", sessionId, current).overallOk()).isTrue();

            // The reported attack: the current record's contract hash rewritten to 64 zeros.
            String zeros = "0".repeat(64);
            String state = Files.readString(statePath);
            Files.writeString(statePath, state.replace(second.contractHash(), zeros));
            StudioCatalogService rewritten = new StudioCatalogService(store, null);
            StudioVerificationResult forged = rewritten.verifyCompile("tenant-a", "assembly-flow", sessionId, current);
            assertThat(forged.available()).isFalse();
            assertThat(forged.overallOk()).isNull();
            assertThat(forged.absentReason().code()).isEqualTo("COMPILE_RECORD_INCONSISTENT");
            assertThat(forged.absentReason().message()).contains(zeros);
            assertThat(rewritten.contractDiff("tenant-a", "assembly-flow", sessionId, 2, null, Optional.of(containerPolicy()))
                    .absentReason().code()).isEqualTo("COMPILE_RECORD_INCONSISTENT");

            // Another compile's signed envelope under this record is refused too.
            Files.writeString(statePath, state.replace(second.signedEnvelopeSha256(), first.signedEnvelopeSha256()));
            StudioVerificationResult swapped = new StudioCatalogService(store, null)
                    .verifyCompile("tenant-a", "assembly-flow", sessionId, current);
            assertThat(swapped.absentReason().code()).isEqualTo("COMPILE_RECORD_INCONSISTENT");
            assertThat(swapped.absentReason().message()).contains("signed envelope");
        } finally {
            restoreProperty("unfurl.studio.signing.privateKey", previousPrivateKey);
            restoreProperty("unfurl.studio.signing.publicKey", previousPublicKey);
            restoreProperty("unfurl.studio.trust.keysDir", previousKeysDir);
        }
    }

    /** A snapshot never carries compile records in: the imported session has none and is compiled again here. */
    @Test
    void importsSnapshotSessionsWithoutCompileRecords(@TempDir Path dir) throws Exception {
        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);
        assertThat(compile(service, created.session()).status()).isEqualTo("COMPILED");
        StudioAssemblySnapshot snapshot = service.saveAssemblySnapshot("tenant-a", "assembly-flow");
        assertThat(snapshot.sessions()).singleElement().satisfies(session -> assertThat(session.compileRecords()).hasSize(1));

        StudioAssemblySnapshot imported = service.loadAssemblySnapshot("tenant-a", "assembly-flow", snapshot);

        assertThat(imported.sessions()).singleElement().satisfies(session -> assertThat(session.compileRecords()).isEmpty());
        assertThat(service.contractDiff("tenant-a", "assembly-flow", created.session().sessionId(), 2, null,
                Optional.empty()).absentReason().code()).isEqualTo("COMPILE_REQUIRED");
    }

    /** Fixture command: one signed compile at the supplied revision with the container policy; returns its record. */
    private static StudioCompileRecord signedCompile(StudioCatalogService service, String sessionId, long revision) {
        StudioCompileDraftCandidateResponse response = service.compileCandidate("tenant-a", "assembly-flow", sessionId,
                new StudioCompileDraftCandidateRequest("tenant-a", "assembly-flow", sessionId, revision, true,
                        containerPolicy()));
        assertThat(response.status()).as(response.reason() + ": " + response.details()).isEqualTo("COMPILED");
        assertThat(response.compileRecord().signed()).isTrue();
        return response.compileRecord();
    }

    // ---- P2b: typed view projections (LLD-fabric-studio-api, StudioViewProjection) ----

    /**
     * Compile-derived view sections follow the P2a rules: COMPILE_REQUIRED before a compile; after it, the transports of
     * every child contract, each mapped to the /dynamic-dcp node of its provider; COMPILE_STALE after an edit.
     */
    @Test
    void projectsCompileDerivedSectionsUnderTheFreshnessRule(@TempDir Path dir) throws Exception {
        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        String sessionId = created.session().sessionId();
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);

        StudioViewProjection before = service.viewProjection("tenant-a", "assembly-flow", sessionId, "topology",
                Optional.of(containerPolicy()));
        assertThat(before.transports().absentReason().code()).isEqualTo("COMPILE_REQUIRED");
        assertThat(before.view()).isEqualTo("topology");
        assertThat(before.revision()).isEqualTo(2);
        assertThat(before.diagnosticArtifacts()).singleElement()
                .satisfies(artifact -> assertThat(artifact.url()).contains("view-topology.json"));

        StudioCompileRecord record = compile(service, created.session()).compileRecord();
        StudioViewProjection topology = service.viewProjection("tenant-a", "assembly-flow", sessionId, "topology",
                Optional.of(containerPolicy()));
        Set<String> nodeIds = service.dynamicDcpProjection("tenant-a", "assembly-flow", sessionId).nodes().stream()
                .map(StudioDynamicDcpNode::nodeId).collect(java.util.stream.Collectors.toSet());
        assertThat(topology.transports().available()).as(String.valueOf(topology.transports().absentReason())).isTrue();
        assertThat(topology.transports().provenance().contractHash()).isEqualTo(record.contractHash());
        assertThat(topology.transports().items()).hasSize(2).allSatisfy(transport -> {
            assertThat(transport.providerNodeId()).isIn(nodeIds);
            assertThat(transport.kind()).isNotBlank();
            assertThat(transport.source()).startsWith("contract.child_contracts[");
        });
        StudioViewProjection data = service.viewProjection("tenant-a", "assembly-flow", sessionId, "data",
                Optional.of(containerPolicy()));
        assertThat(data.dataMappings().provenance().contractHash()).isEqualTo(record.contractHash());
        assertThat(data.externalOwners()).as("only the requested view's sections are set").isNull();

        removeComponent(service, created.session(), "uploaded:foundry.jar", 2);
        StudioViewProjection moved = service.viewProjection("tenant-a", "assembly-flow", sessionId, "topology",
                Optional.of(containerPolicy()));
        assertThat(moved.transports().absentReason().code()).isEqualTo("COMPILE_STALE");
        assertThat(moved.transports().items()).isEmpty();
    }

    /**
     * A node whose catalog entry has no admitted claim is a diagnostic and nothing is projected for it; the runtime view
     * carries the verification from the same verify method and says when no component declares monitoring.
     */
    @Test
    void reportsUngroundedNodesAndAbsentRuntimeData(@TempDir Path dir) throws Exception {
        StudioCatalogService displayOnly = new StudioCatalogService();
        StudioCreateDraftCompositionResponse demo = displayOnly.createDraftSession("tenant-a", "assembly-demo",
                new StudioCreateDraftCompositionRequest("tenant-a", "assembly-demo", "sha256:catalog", "", "", "",
                        "alice", "Alice"));
        addComponent(displayOnly, demo.session(), "com.unfurl:storage-s3:1.2.0", 0);
        StudioViewProjection flow = displayOnly.viewProjection("tenant-a", "assembly-demo", demo.session().sessionId(),
                "flow", Optional.empty());
        assertThat(flow.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("CATALOG_ENTRY_NOT_ADMITTED");
            assertThat(diagnostic.message()).contains("com.unfurl:storage-s3:1.2.0");
        });
        assertThat(flow.externalOwners().absentReason().code()).isEqualTo("NO_COMPONENTS");

        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);
        StudioViewProjection runtime = service.viewProjection("tenant-a", "assembly-flow",
                created.session().sessionId(), "runtime", Optional.empty());
        assertThat(runtime.monitoring().absentReason().code()).isEqualTo("NO_MONITORING_DECLARED");
        assertThat(runtime.verification().absentReason().code()).isEqualTo("COMPILE_REQUIRED");
        assertThat(runtime.diagnostics()).isEmpty();

        assertThatThrownBy(() -> service.viewProjection("tenant-a", "assembly-flow", created.session().sessionId(),
                "overlays", Optional.empty())).hasMessageStartingWith("VIEW_UNKNOWN");
        assertThatThrownBy(() -> service.viewProjection("tenant-a", "assembly-flow", "", "flow", Optional.empty()))
                .hasMessageStartingWith("VIEW_SESSION_REQUIRED");
    }

    // ---- P2c: perspective overlays (LLD-fabric-studio-api, StudioOverlayAnnotations) ----

    /**
     * Compile-derived overlay entries follow the P2a rules: before a compile the decision audit is a stale source and
     * Performance answers COMPILE_REQUIRED; after it the audit findings carry the record's provenance; after an edit they
     * are left out again.
     */
    @Test
    void overlaysReadTheCurrentCompileOnly(@TempDir Path dir) throws Exception {
        StudioCatalogService service = flowfoundrySessionService(dir);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
        String sessionId = created.session().sessionId();
        addComponent(service, created.session(), "uploaded:flow.jar", 0);
        addComponent(service, created.session(), "uploaded:foundry.jar", 1);

        StudioOverlayAnnotations performance = service.overlayAnnotations("tenant-a", "assembly-flow", sessionId,
                "performance", "topology", Optional.of(containerPolicy()));
        assertThat(performance.available()).isFalse();
        assertThat(performance.absentReason().code()).isEqualTo("COMPILE_REQUIRED");
        StudioOverlayAnnotations before = service.overlayAnnotations("tenant-a", "assembly-flow", sessionId,
                "compliance", "flow", Optional.of(containerPolicy()));
        assertThat(before.available()).as("the trust-policy finding is always there").isTrue();
        assertThat(before.staleSources()).singleElement()
                .satisfies(stale -> assertThat(stale.reason().code()).isEqualTo("COMPILE_REQUIRED"));

        StudioCompileRecord record = compile(service, created.session()).compileRecord();
        StudioOverlayAnnotations after = service.overlayAnnotations("tenant-a", "assembly-flow", sessionId,
                "compliance", "flow", Optional.of(containerPolicy()));
        assertThat(after.staleSources()).isEmpty();
        assertThat(after.findings()).anySatisfy(finding -> {
            assertThat(finding.label()).isEqualTo("Selection mode");
            assertThat(finding.value()).as("a Studio draft is operator-composed").isEqualTo("MANUAL");
            assertThat(finding.contractHash()).isEqualTo(record.contractHash());
            assertThat(finding.sourceRevision()).isEqualTo(record.revision());
        });
        assertThat(before.provenance()).as("no compile is recorded yet").isNull();
        assertThat(after.provenance().contractHash()).isEqualTo(record.contractHash());
        StudioOverlayAnnotations compiledPerformance = service.overlayAnnotations("tenant-a", "assembly-flow", sessionId,
                "performance", "topology", Optional.of(containerPolicy()));
        assertThat(compiledPerformance.provenance().contractHash())
                .as("the record is named even when it sets no timeout").isEqualTo(record.contractHash());
        assertThat(service.overlayAnnotations("tenant-a", "assembly-flow", sessionId, "security", "flow",
                Optional.of(containerPolicy())).provenance()).as("security reads claims only").isNull();
        assertThat(compiledPerformance.absentReason() == null
                ? compiledPerformance.annotations().stream().allMatch(a -> record.contractHash().equals(a.contractHash()))
                : "NO_TIMEOUTS_DECLARED".equals(compiledPerformance.absentReason().code()))
                .as(String.valueOf(compiledPerformance.absentReason())).isTrue();

        removeComponent(service, created.session(), "uploaded:foundry.jar", 2);
        StudioOverlayAnnotations moved = service.overlayAnnotations("tenant-a", "assembly-flow", sessionId,
                "compliance", "flow", Optional.of(containerPolicy()));
        assertThat(moved.findings()).noneSatisfy(finding -> assertThat(finding.contractHash()).isNotNull());
        assertThat(moved.staleSources()).singleElement()
                .satisfies(stale -> assertThat(stale.reason().code()).isEqualTo("COMPILE_STALE"));
        assertThat(moved.provenance().contractHash()).as("the stale record it describes").isEqualTo(record.contractHash());

        StudioOverlayAnnotations resilience = service.overlayAnnotations("tenant-a", "assembly-flow", sessionId,
                "resilience", "flow", Optional.empty());
        assertThat(resilience.provenance()).as("resilience reads claims only").isNull();
        assertThat(resilience.available() ? "available" : resilience.absentReason().code())
                .isIn("available", "NO_FAULTS_DECLARED");
        assertThatThrownBy(() -> service.overlayAnnotations("tenant-a", "assembly-flow", sessionId, "cost", "flow",
                Optional.empty())).hasMessageStartingWith("OVERLAY_UNKNOWN");
        assertThatThrownBy(() -> service.overlayAnnotations("tenant-a", "assembly-flow", sessionId, "security", "graph",
                Optional.empty())).hasMessageStartingWith("VIEW_UNKNOWN");
    }

    /** Fixture command: applies one REMOVE_COMPONENT intent at the supplied revision. */
    private static void removeComponent(StudioCatalogService service, StudioDraftSession session, String catalogEntryId,
                                        long revision) {
        StudioIntentRequest intent = new StudioIntentRequest();
        intent.tenantId = session.tenantId();
        intent.assemblyId = session.assemblyId();
        intent.sessionId = session.sessionId();
        intent.baseRevision = revision;
        intent.type = "REMOVE_COMPONENT";
        intent.collaboratorId = "alice";
        intent.put("catalogEntryId", catalogEntryId);
        assertThat(service.applyIntent(session.tenantId(), session.assemblyId(), session.sessionId(), intent).status())
                .isEqualTo("VALID");
    }

    /** Fixture policy: asks the deployment resolver for in-process shapes. */
    private static StudioDeploymentPolicyDraft inProcessPolicy() {
        return new StudioDeploymentPolicyDraft(List.of("IN_PROCESS_LIBRARY"), List.of(), List.of(),
                new StudioDeploymentRuntimeDraft("21", true, true, true, null));
    }

    private static final String COMPANION = "com.example:broker-http:1.0.0";
    private static final String REGISTRAR_SHAPE = "com.unfurl.foundry.substrate.offers.ToolCapabilityRegistrar";

    /** Fixture manifest: a service that hosts in-process components of {@link #REGISTRAR_SHAPE}. */
    private static String hostManifestYaml(String name, String capability) {
        return catalogManifestYaml(name, capability) + """
                  component_host:
                    registrar_shape: %s
                    shared_api_prefixes: [com.unfurl.foundry.substrate.]
                """.formatted(REGISTRAR_SHAPE);
    }

    /** Fixture manifest: an in-process-only component hosted by {@link #REGISTRAR_SHAPE}, naming one companion. */
    private static String hostedManifestYaml(String name, String capability, String companion) {
        return catalogManifestYaml(name, capability)
                .replace("supported_shapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]", "supported_shapes: [IN_PROCESS_LIBRARY]")
                + """
                  hosted_activation:
                    registrar_shapes: [%s]
                    companions: [%s]
                """.formatted(REGISTRAR_SHAPE, companion);
    }

    /** Fixture jar: a companion carrying exactly one {@code pom.properties} for {@link #COMPANION}. */
    private static byte[] companionJar(Path dir, String fileName, byte[] payload) throws Exception {
        Path jar = dir.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/maven/com.example/broker-http/pom.properties"));
            output.write("groupId=com.example\nartifactId=broker-http\nversion=1.0.0\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("com/example/http/Client.class"));
            output.write(payload);
            output.closeEntry();
        }
        return Files.readAllBytes(jar);
    }

    /**
     * A persisted manifest whose claim no longer matches its pinned hash is not compiled.
     */
    @Test
    void refusesCompileWhenThePersistedManifestNoLongerMatchesItsHash(@TempDir Path dir) throws Exception {
        Path statePath = dir.resolve("studio-state.json");
        StudioStateStore store = new StudioStateStore(statePath);
        StudioCatalogService first = flowfoundrySessionService(dir, store);
        StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(first);
        addComponent(first, created.session(), "uploaded:flow.jar", 0);
        addComponent(first, created.session(), "uploaded:foundry.jar", 1);
        String state = Files.readString(statePath);
        assertThat(state).contains("description: Provides agent.run");
        Files.writeString(statePath, state.replace("description: Provides agent.run", "description: Provides agent.run, edited"));

        StudioCompileDraftCandidateResponse response = compile(new StudioCatalogService(store, null), created.session());

        assertThat(response.status()).isNotEqualTo("COMPILED");
        assertThat(response.reason()).isEqualTo("CATALOG_ENTRY_NOT_ADMITTED");
        assertThat(response.details()).contains("uploaded:foundry.jar").contains("no longer matches its pinned hash");
    }

    /**
     * A built-in display-only entry has no claim read from an artifact, so compile refuses it instead of building a
     * stand-in claim from its visual metadata.
     */
    @Test
    void refusesCompileOfADisplayOnlyEntry() {
        StudioCatalogService service = new StudioCatalogService();
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-demo",
                        "sha256:catalog",
                        "",
                        "trust-prod",
                        "",
                        "alice",
                        "Alice"));
        addComponent(service, created.session(), "com.unfurl:storage-s3:1.2.0", 0);

        StudioCompileDraftCandidateResponse response = compile(service, created.session());

        assertThat(response.reason()).isEqualTo("CATALOG_ENTRY_NOT_ADMITTED");
        assertThat(response.details()).contains("com.unfurl:storage-s3:1.2.0").contains("display-only");
    }

    /**
     * Regression test: signed Studio compile must also emit the broker-consumable DCP runtime
     * bundle that Flow Step 16 hydrates from provider claims and frozen child contracts.
     */
    @Test
    void signedCompileEmitsDcpRuntimeBundleForFlowHydration(@TempDir Path dir) throws Exception {
        KeyPair keys = generateStudioSigningKeyPair();
        Path privateKey = writePrivateKeyPem(dir, "studio-private.pem", keys);
        Path publicKey = writePublicKeyPem(dir, "studio-public.pem", keys);
        String previousPrivateKey = System.getProperty("unfurl.studio.signing.privateKey");
        String previousPublicKey = System.getProperty("unfurl.studio.signing.publicKey");
        try {
            System.setProperty("unfurl.studio.signing.privateKey", privateKey.toString());
            System.setProperty("unfurl.studio.signing.publicKey", publicKey.toString());

            StudioCatalogService service = flowfoundrySessionService(dir);
            StudioCreateDraftCompositionResponse created = flowfoundryDraftSession(service);
            addComponent(service, created.session(), "uploaded:flow.jar", 0);
            addComponent(service, created.session(), "uploaded:foundry.jar", 1);

            StudioCompileDraftCandidateResponse response = service.compileCandidate(
                    "tenant-a",
                    "assembly-flow",
                    created.session().sessionId(),
                    new StudioCompileDraftCandidateRequest(
                            "tenant-a",
                            "assembly-flow",
                            created.session().sessionId(),
                            2,
                            true,
                            containerPolicy()));

            assertThat(response.status())
                    .as(response.reason() + ": " + response.details())
                    .isEqualTo("COMPILED");
            assertThat(response.signedContractArtifact()).isNotNull();
            assertThat(response.warnings()).isEmpty();

            assertThat(response.supportArtifacts())
                    .extracting(StudioExportArtifact::url)
                    .anyMatch(url -> url.contains("signed-compiled-contract.yaml"))
                    .anyMatch(url -> url.contains("dcp-runtime-bundle.zip"));
            assertThat(response.diagnosticArtifacts())
                    .extracting(StudioExportArtifact::url)
                    .anyMatch(url -> url.contains("compiled-contract-envelope.yaml"))
                    .anyMatch(url -> url.contains("compile-response.json"));

            StudioExportArtifact runtimeBundle = response.supportArtifacts().stream()
                    .filter(artifact -> "application/zip".equals(artifact.mediaType()))
                    .findFirst()
                    .orElseThrow();
            assertThat(runtimeBundle.url()).contains("dcp-runtime-bundle.zip");
            assertThat(service.exportArtifactContent("tenant-a", runtimeBundle.artifactId(), runtimeBundle.sha256()))
                    .hasValueSatisfying(content -> {
                        assertThat(content.mediaType()).isEqualTo("application/zip");
                        try {
                            Set<String> entries = zipEntries(content.bytes());
                            assertThat(entries)
                                    .anyMatch(entry -> entry.startsWith("claims/") && entry.endsWith(".claim.json"))
                                    .anyMatch(entry -> entry.startsWith("contracts/frozen/")
                                            && entry.contains("agent-run")
                                            && entry.endsWith(".frozen.json"));
                            ClaimValidator validator = new ClaimValidator();
                            assertThat(zipClaims(content.bytes()))
                                    .isNotEmpty()
                                    .allSatisfy(claim -> assertThat(validator.validate(claim).valid())
                                            .as("runtime bundle claim should be DCP-valid: " + claim.identity().uri())
                                            .isTrue());
                        } catch (Exception ex) {
                            throw new AssertionError("DCP runtime bundle is not a readable zip", ex);
                        }
                    });
        } finally {
            restoreProperty("unfurl.studio.signing.privateKey", previousPrivateKey);
            restoreProperty("unfurl.studio.signing.publicKey", previousPublicKey);
        }
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

    /**
     * Regression test: draft-session projection must replay Step 9 membership
     * intents instead of rendering every tenant catalog entry.
     */
    @Test
    void projectsDynamicDcpGraphFromDraftSessionInventory() {
        StudioCatalogService service = new StudioCatalogService();
        service.createAssembly("tenant-a", new StudioCreateAssemblyRequest(
                "assembly-checkout",
                "Checkout Platform",
                "kubernetes-prod"));
        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-checkout",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-checkout",
                        "sha256:catalog",
                        "",
                        "trust-dev",
                        "",
                        "alice",
                        "Alice"));
        addComponent(service, created.session(), "com.unfurl:validation-service:1.1.0", 0);

        StudioDynamicDcpProjection projection = service.dynamicDcpProjection(
                "tenant-a",
                "assembly-checkout",
                created.session().sessionId());

        assertThat(projection.nodes())
                .filteredOn(StudioDynamicDcpNode::replacementAllowed)
                .extracting(StudioDynamicDcpNode::catalogEntryId)
                .containsExactly("com.unfurl:validation-service:1.1.0");
        assertThat(projection.nodes())
                .filteredOn(StudioDynamicDcpNode::replacementAllowed)
                .extracting(StudioDynamicDcpNode::catalogEntryId)
                .doesNotContain("com.unfurl:storage-s3:1.2.0");
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
    void persistsTenantCatalogAssembliesAndLayouts(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("studio-state.json"));
        StudioCatalogService first = new StudioCatalogService(store);

        first.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(jarDraft(dir, "payment.jar", catalogManifestYaml("payment", "payment.process")))));
        first.createAssembly("tenant-a", new StudioCreateAssemblyRequest(
                "assembly-checkout",
                "Checkout Platform",
                "kubernetes-prod"));
        first.saveLayout("tenant-a", "assembly-checkout", StudioLayoutStateRequest.of(
                "CHILD_DCP",
                "payment",
                Map.of("distance", 5.5),
                List.of("inspect payment replacement"),
                "FLOW", List.of("SECURITY"), "SECURITY", "THREE_D", "EXPLODED"));

        StudioCatalogService second = new StudioCatalogService(store);

        assertThat(second.listCatalogVisuals("tenant-a").entries())
                .filteredOn(entry -> "uploaded:payment.jar".equals(entry.catalogEntryId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.catalogManifest())
                        .isEqualTo(catalogManifestYaml("payment", "payment.process")));
        assertThat(second.listAssemblies("tenant-a").assemblies())
                .extracting(StudioAssemblySummary::assemblyId)
                .contains("assembly-checkout");
        assertThat(second.layout("tenant-a", "assembly-checkout"))
                .satisfies(layout -> {
                    assertThat(layout.activeView()).isNull();
                    assertThat(layout.activeViewpoint()).isEqualTo("FLOW");
                    assertThat(layout.activeOverlays()).containsExactly("SECURITY");
                    assertThat(layout.layout()).isEqualTo("EXPLODED");
                    assertThat(layout.semanticZoomLevel()).isEqualTo("CHILD_DCP");
                    assertThat(layout.selectedSurface()).isEqualTo("payment");
                    assertThat(layout.annotations()).contains("inspect payment replacement");
                });
    }

    @Test
    void tracksTenantFileVersionsAndBindsDraftsToSelectedCatalogFile(@TempDir Path dir) throws Exception {
        StudioStateStore store = new StudioStateStore(dir.resolve("studio-state.json"));
        StudioCatalogService service = new StudioCatalogService(store);

        List<StudioFileRecord> initialCatalogFiles = service.listFiles("tenant-a", "CATALOG", "", "");
        assertThat(initialCatalogFiles).hasSize(1);
        StudioFileRecord initialCatalog = initialCatalogFiles.get(0);
        assertThat(initialCatalog.tenantId()).isEqualTo("tenant-a");
        assertThat(initialCatalog.logicalFileId()).isEqualTo("tenant-catalog");
        assertThat(initialCatalog.version()).isEqualTo(1);

        service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-checkout",
                List.of(jarDraft(dir, "payment.jar", catalogManifestYaml("payment", "payment.process")))));

        List<StudioFileRecord> catalogFiles = service.listFiles("tenant-a", "CATALOG", "", "");
        assertThat(catalogFiles).hasSize(2);
        StudioFileRecord selectedCatalog = catalogFiles.get(0);
        assertThat(selectedCatalog.version()).isEqualTo(2);
        assertThat(selectedCatalog.filePath()).contains("/studio/tenants/tenant-a/files/");

        StudioCatalogVisualsResponse selectedCatalogVisuals =
                service.listCatalogVisuals("tenant-a", selectedCatalog.fileId());
        assertThat(selectedCatalogVisuals.catalogHash()).startsWith("sha256:");
        assertThat(service.fileContent("tenant-a", selectedCatalog.fileId(), selectedCatalog.sha256()))
                .isPresent()
                .get()
                .satisfies(content -> {
                    assertThat(content.mediaType()).isEqualTo("application/json");
                    assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).contains("uploaded:payment.jar");
                });

        StudioCreateDraftCompositionResponse created = service.createDraftSession(
                "tenant-a",
                "assembly-checkout",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-checkout",
                        "",
                        "",
                        "",
                        "",
                        "alice",
                        "Alice",
                        selectedCatalog.fileId(),
                        "Checkout Flow Draft"));

        assertThat(created.session().catalogFileId()).isEqualTo(selectedCatalog.fileId());
        assertThat(created.session().baseCatalogHash()).isEqualTo(selectedCatalogVisuals.catalogHash());
        assertThat(created.session().displayName()).isEqualTo("Checkout Flow Draft");
        assertThat(service.listFiles("tenant-a", "", created.session().sessionId(), ""))
                .extracting(StudioFileRecord::fileId)
                .contains(selectedCatalog.fileId());
        assertThat(service.listSessionHistory("tenant-a", "assembly-checkout"))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.displayName()).isEqualTo("Checkout Flow Draft");
                    assertThat(history.catalogFileId()).isEqualTo(selectedCatalog.fileId());
                    assertThat(history.linkedFileCount()).isGreaterThanOrEqualTo(1);
                });
        assertThat(service.listFiles("tenant-b", "CATALOG", "", ""))
                .extracting(StudioFileRecord::fileId)
                .doesNotContain(selectedCatalog.fileId());
        assertThatThrownBy(() -> service.listCatalogVisuals("tenant-b", selectedCatalog.fileId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");

        StudioCatalogService reloaded = new StudioCatalogService(store);
        assertThat(reloaded.listSessionHistory("tenant-a", "assembly-checkout"))
                .singleElement()
                .extracting(StudioSessionHistoryItem::catalogFileId)
                .isEqualTo(selectedCatalog.fileId());
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
                com.unfurl.dcp.fault.FaultPolicy.empty(),
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
%s
                    stability: STABLE
                    version: 1.0.0
                    metered: false
                integration_ports:
                  ports: {}
                faults:
                  emitted: []
                metadata:
                  dcp_version: 0.2.0
                  claim_version: 1.0.0
                  created_at: 1970-01-01T00:00:00Z
                """.formatted(
                name,
                name,
                name,
                capability,
                capability,
                capability,
                capability,
                indent(offerDetailsYaml(capability), 22));
    }

    /**
     * Fixture formatter: returns DCP offer details for capabilities whose tests need governed modes.
     */
    private static String offerDetailsYaml(String capability) {
        if ("agent.run".equals(capability)) {
            return """
                    details:
                      execution_modes: [simple, harness]
                    """.stripTrailing();
        }
        return "details: {}";
    }

    /**
     * Fixture service: admits Flow and Foundry manifest JARs, then stores extracted
     * workflow/agent needs for a tenant assembly.
     */
    private static StudioCatalogService flowfoundrySessionService(Path dir) throws Exception {
        return flowfoundrySessionService(dir, null);
    }

    /**
     * Fixture service: optionally uses a persistent Studio state store so tests can
     * exercise restart behavior without changing the runbook catalog/session setup.
     */
    private static StudioCatalogService flowfoundrySessionService(Path dir, StudioStateStore store) throws Exception {
        StudioCatalogService service = new StudioCatalogService(store, null);
        StudioCatalogAdmissionResponse admission = service.admit("tenant-a", new StudioCatalogAdmissionRequest(
                "assembly-flow",
                List.of(
                        new StudioComponentArtifactDraft(
                                "flow.jar",
                                "",
                                "",
                                Base64.getEncoder().encodeToString(jarWithManifest(
                                        dir,
                                        catalogManifestYaml("flow", "workflow.execute")))),
                        new StudioComponentArtifactDraft(
                                "foundry.jar",
                                "",
                                "",
                                Base64.getEncoder().encodeToString(jarWithManifest(
                                        dir,
                                        catalogManifestYaml("foundry", "agent.run")))))));
        assertThat(admission.status()).as("flowfoundry fixture admission results: %s", admission.results())
                .isEqualTo("VERIFIED");
        service.extractNeeds(
                "tenant-a",
                "assembly-flow",
                new StudioNeedsExtractionRequest(
                        "Flowfoundry Export",
                        List.of("workflow.yaml", "workload.agent.yaml"),
                        "containerized-local"));
        return service;
    }

    /**
     * Fixture builder: creates a Studio draft session pointing at the extracted
     * Flowfoundry needs id.
     */
    private static StudioCreateDraftCompositionResponse flowfoundryDraftSession(StudioCatalogService service) {
        return service.createDraftSession(
                "tenant-a",
                "assembly-flow",
                new StudioCreateDraftCompositionRequest(
                        "tenant-a",
                        "assembly-flow",
                        "sha256:catalog",
                        "assembly-flow-extracted-needs",
                        "trust-prod",
                        "",
                        "alice",
                        "Alice"));
    }

    /**
     * Fixture command: applies one ADD_COMPONENT intent at the supplied revision.
     */
    private static void addComponent(
            StudioCatalogService service,
            StudioDraftSession session,
            String catalogEntryId,
            long revision
    ) {
        StudioIntentRequest intent = new StudioIntentRequest();
        intent.tenantId = session.tenantId();
        intent.assemblyId = session.assemblyId();
        intent.sessionId = session.sessionId();
        intent.baseRevision = revision;
        intent.type = "ADD_COMPONENT";
        intent.collaboratorId = "alice";
        intent.put("catalogEntryId", catalogEntryId);
        StudioIntentResponse response = service.applyIntent(
                session.tenantId(),
                session.assemblyId(),
                session.sessionId(),
                intent);
        assertThat(response.status()).isEqualTo("VALID");
    }

    /**
     * Fixture policy: asks the deployment resolver to choose containerized runtime
     * shapes from the component shape profiles embedded in the test manifests.
     */
    private static StudioDeploymentPolicyDraft containerPolicy() {
        return new StudioDeploymentPolicyDraft(
                List.of("CONTAINERIZED_SERVICE"),
                List.of(),
                List.of(),
                new StudioDeploymentRuntimeDraft("21", true, true, true, null));
    }

    /**
     * Fixture manifest: wraps a pure DCP claim with catalog metadata and a shape profile
     * that supports containerized deployment.
     */
    private static String catalogManifestYaml(String name, String capability) {
        return """
                claim:
                %s
                catalog:
                  lifecycle:
                    status: ACTIVE
                  artifact:
                    coordinates: com.unfurl:%s:1.0.0
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: IN_PROCESS
                    supported_modes: [IN_PROCESS]
                  component_shape_profile:
                    default_shape: IN_PROCESS_LIBRARY
                    supported_shapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                    shape_runtime: {}
                """.formatted(indent(validClaimYaml(name, capability), 2), name);
    }

    /**
     * Fixture formatter: indents every line of a YAML block by the requested spaces.
     */
    private static String indent(String yaml, int spaces) {
        String prefix = " ".repeat(spaces);
        return yaml.lines()
                .map(line -> prefix + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /**
     * Fixture helper: compiles a draft session at its current revision with the container deployment policy.
     */
    private static StudioCompileDraftCandidateResponse compile(StudioCatalogService service, StudioDraftSession session) {
        long revision = service.draftSession(session.tenantId(), session.assemblyId(), session.sessionId()).sceneRevision();
        return service.compileCandidate(
                session.tenantId(),
                session.assemblyId(),
                session.sessionId(),
                new StudioCompileDraftCandidateRequest(
                        session.tenantId(),
                        session.assemblyId(),
                        session.sessionId(),
                        revision,
                        false,
                        containerPolicy()));
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
     * Fixture helper: an admission draft carrying the bytes of a jar named {@code fileName} whose
     * {@code META-INF/unfurl-catalog.yaml} is {@code manifestYaml}.
     */
    private static StudioComponentArtifactDraft jarDraft(Path dir, String fileName, String manifestYaml) throws Exception {
        Path jar = dir.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            output.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new StudioComponentArtifactDraft(fileName, "", "", Base64.getEncoder().encodeToString(Files.readAllBytes(jar)));
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

    /**
     * Fixture helper: reads DCP runtime-bundle provider claims so tests can validate the bundle with
     * DCP's protocol validator, not just assert the ZIP contains files.
     */
    private static List<Claim> zipClaims(byte[] bytes) throws Exception {
        ObjectMapper mapper = StudioJson.mapper().copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        List<Claim> claims = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()
                        && entry.getName().startsWith("claims/")
                        && entry.getName().endsWith(".claim.json")) {
                    claims.add(mapper.readValue(zip.readAllBytes(), Claim.class));
                }
            }
        }
        return claims;
    }

    /**
     * Test Fixture Factory: creates a P-256 signing key pair that matches the
     * Studio signing configuration contract without depending on another test package.
     */
    private static KeyPair generateStudioSigningKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Test Fixture Writer: writes the private signing key in PKCS#8 PEM form so
     * Studio can load it through the same file-based configuration path used in runtime.
     */
    private static Path writePrivateKeyPem(Path dir, String fileName, KeyPair keys) throws IOException {
        return writePem(dir, fileName, "PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    /**
     * Test Fixture Writer: writes the public signing key in X.509 PEM form so
     * Studio can verify signatures through the same file-based configuration path used in runtime.
     */
    private static Path writePublicKeyPem(Path dir, String fileName, KeyPair keys) throws IOException {
        return writePem(dir, fileName, "PUBLIC KEY", keys.getPublic().getEncoded());
    }

    /**
     * Test Fixture Encoder: writes a PEM block with conventional 64-character
     * Base64 lines, matching the format accepted by the Studio signing loader.
     */
    private static Path writePem(Path dir, String fileName, String type, byte[] der) throws IOException {
        Path path = dir.resolve(fileName);
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n"
                + encoded
                + "\n-----END " + type + "-----\n";
        Files.writeString(path, pem, StandardCharsets.US_ASCII);
        return path;
    }

    /**
     * Fixture helper: restores a JVM property after a test mutates Studio signing
     * configuration, keeping signing-enabled tests isolated from the rest of the suite.
     */
    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }
}
