package com.unfurl.fabric.studio;

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
        service.saveLayout("tenant-a", "assembly-checkout", new StudioLayoutStateRequest(
                "Exploded",
                "CHILD_DCP",
                "payment",
                Map.of("distance", 5.5),
                List.of("inspect payment replacement")));
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
                        faults:
                          emitted: []
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
                      details: {}
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
                """.formatted(name, name, name, capability, capability, capability, capability);
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
        service.admit("tenant-a", new StudioCatalogAdmissionRequest(
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
                  faults:
                    emitted: []
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
