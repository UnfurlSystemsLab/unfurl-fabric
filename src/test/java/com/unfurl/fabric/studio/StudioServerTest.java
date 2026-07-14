package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class StudioServerTest {

    @Test
    void servesHealthAndResolveDeployment(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = writeNeeds(dir, "storage.put");

        try (StudioServer server = started()) {
            HttpResponse<String> health = get(server, "/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"UP\"", "unfurl-fabric-studio", "\"eventBus\"");
            HttpResponse<String> ready = get(server, "/ready");
            assertThat(ready.statusCode()).isEqualTo(200);
            assertThat(ready.body()).contains("\"READY\"", "\"provider\":\"in-memory\"");

            HttpResponse<String> resolved = post(server, "/studio/deployment/resolve", """
                    {
                      "catalogPath": "%s",
                      "needsPath": "%s",
                      "autoSelectBest": false,
                      "deploymentPolicy": {
                        "preferredShapes": ["CONTAINERIZED_SERVICE"],
                        "disallowedShapes": [],
                        "requireIsolationForCapabilityPatterns": [],
                        "runtime": {
                          "javaVersion": "21",
                          "springBoot": true,
                          "kubernetes": true,
                          "serviceMesh": true
                        }
                      }
                    }
                    """.formatted(jsonPath(catalog), jsonPath(needs)));

            assertThat(resolved.statusCode()).isEqualTo(200);
            assertThat(resolved.body())
                    .contains("\"status\":\"RESOLVED\"")
                    .contains("\"deploymentShape\":\"CONTAINERIZED_SERVICE\"");
        }
    }

    @Test
    void servesTenantScopedStudioCatalogAdmissionAndNeedsExtraction() throws Exception {
        try (StudioServer server = started()) {
            HttpResponse<String> catalog = get(server, "/studio/tenants/tenant-a/catalog");
            assertThat(catalog.statusCode()).isEqualTo(200);
            assertThat(catalog.body()).contains("\"catalogHash\":\"sha256:", "validation-service");

            HttpResponse<String> removal = delete(
                    server,
                    "/studio/tenants/tenant-a/catalog/"
                            + URLEncoder.encode("com.unfurl:storage-s3:1.2.0", StandardCharsets.UTF_8));
            assertThat(removal.statusCode()).isEqualTo(200);
            assertThat(removal.body())
                    .contains("\"status\":\"REMOVED\"")
                    .contains("\"catalogEntryId\":\"com.unfurl:storage-s3:1.2.0\"")
                    .doesNotContain("\"catalogEntryId\":\"com.unfurl:storage-s3:1.2.0\",\"claimHash\"");

            HttpResponse<String> asset = get(server, "/studio/tenants/tenant-a/assets/validation-service-model");
            assertThat(asset.statusCode()).isEqualTo(200);
            assertThat(asset.body())
                    .contains("\"status\":\"HASH_PINNED\"")
                    .contains("META-INF/visual/validation-service.glb")
                    .contains("sha256=");

            HttpResponse<String> admission = post(server, "/studio/tenants/tenant-a/catalog/admissions", """
                    {
                      "assemblyId": "assembly-checkout",
                      "artifacts": [
                        {
                          "fileName": "payment.yaml",
                          "claimYaml": %s
                        }
                      ]
                    }
                    """.formatted(jsonString(validClaimYaml("payment", "payment.process"))));
            assertThat(admission.statusCode()).isEqualTo(200);
            assertThat(admission.body())
                    .contains("\"status\":\"VERIFIED\"")
                    .contains("\"catalogEntryId\":\"uploaded:payment.yaml\"")
                    .contains("\"diagnostics\":[]")
                    .contains("\"claimBundleArtifact\"");
            StudioCatalogAdmissionResponse admissionBody = StudioJson.mapper()
                    .readValue(admission.body(), StudioCatalogAdmissionResponse.class);
            assertThat(admissionBody.diagnosticArtifacts()).hasSize(1);
            StudioExportArtifact admissionDiagnostic = admissionBody.diagnosticArtifacts().get(0);
            assertThat(admissionDiagnostic.mediaType()).isEqualTo("application/json");
            assertThat(admissionDiagnostic.url()).contains("/studio/tenants/tenant-a/diagnostic-artifacts/");

            HttpResponse<String> diagnostic = get(server, admissionDiagnostic.url());
            assertThat(diagnostic.statusCode()).isEqualTo(200);
            assertThat(diagnostic.headers().firstValue("Content-Type")).contains("application/json");
            assertThat(diagnostic.body()).contains("\"status\" : \"VERIFIED\"", "\"claimBundleArtifact\"");

            HttpResponse<String> catalogSnapshot = get(server, "/studio/tenants/tenant-a/catalog/snapshot");
            assertThat(catalogSnapshot.statusCode()).isEqualTo(200);
            assertThat(catalogSnapshot.body())
                    .contains("\"tenantId\":\"tenant-a\"")
                    .contains("\"catalogHash\":\"sha256:")
                    .contains("\"entries\"");
            HttpResponse<String> catalogLoaded = post(
                    server,
                    "/studio/tenants/tenant-b/catalog/snapshot",
                    catalogSnapshot.body());
            assertThat(catalogLoaded.statusCode()).isEqualTo(200);
            assertThat(catalogLoaded.body())
                    .contains("\"catalogHash\":\"sha256:")
                    .contains("\"catalogEntryId\":\"uploaded:payment.yaml\"");

            HttpResponse<String> staleDiagnostic = get(
                    server,
                    admissionDiagnostic.url().replace(admissionDiagnostic.sha256(), "sha256:stale"));
            assertThat(staleDiagnostic.statusCode()).isEqualTo(404);

            HttpResponse<byte[]> claimBundle = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri(server, admissionBody.claimBundleArtifact().url()))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(claimBundle.statusCode()).isEqualTo(200);
            assertThat(claimBundle.headers().firstValue("Content-Type")).contains("application/zip");
            assertThat(zipEntries(claimBundle.body()))
                    .contains("claims/01-payment-yaml.claim.yaml", "admission-manifest.yaml", "diagnostics.json");

            HttpResponse<String> needs = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-checkout/needs/extract",
                    """
                    {
                      "targetApplicationName": "Checkout Platform",
                      "fileNames": ["workflow.yaml"],
                      "defaultDeploymentTarget": "kubernetes-prod"
                    }
                    """);
            assertThat(needs.statusCode()).isEqualTo(200);
            assertThat(needs.body())
                    .contains("\"needsId\":\"assembly-checkout-extracted-needs\"")
                    .contains("workflow.execute")
                    .contains("\"defaultDeploymentTarget\":\"kubernetes-prod\"");

            HttpResponse<String> created = post(server, "/studio/tenants/tenant-a/assemblies", """
                    {
                      "assemblyId": "assembly-payments",
                      "targetApplicationName": "Payments Platform",
                      "defaultDeploymentTarget": "kubernetes-prod"
                    }
                    """);
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(created.body()).contains("\"assemblyId\":\"assembly-payments\"");

            HttpResponse<String> saved = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-payments/drafts/save",
                    """
                    {
                      "targetApplicationName": "Payments Platform",
                      "needsId": "assembly-payments-extracted-needs",
                      "deploymentTarget": "kubernetes-prod",
                      "deploymentShape": "CONTAINERIZED_SERVICE",
                      "currentCandidateId": "cand-abc123",
                      "sceneRevision": 8
                    }
                    """);
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body())
                    .contains("\"status\":\"SAVED\"")
                    .contains("\"currentCandidateId\":\"cand-abc123\"");

            HttpResponse<String> layoutSaved = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-payments/layout",
                    """
                    {
                      "activeView": "Exploded",
                      "semanticZoomLevel": "CHILD_DCP",
                      "selectedSurface": "payment",
                      "camera": { "distance": 5.5 },
                      "annotations": ["inspect payment replacement"]
                    }
                    """);
            assertThat(layoutSaved.statusCode()).isEqualTo(200);
            assertThat(layoutSaved.body())
                    .contains("\"activeView\":\"Exploded\"")
                    .contains("\"semanticZoomLevel\":\"CHILD_DCP\"");

            HttpResponse<String> layout = get(server, "/studio/tenants/tenant-a/assemblies/assembly-payments/layout");
            assertThat(layout.statusCode()).isEqualTo(200);
            assertThat(layout.body())
                    .contains("\"selectedSurface\":\"payment\"")
                    .contains("inspect payment replacement");

            HttpResponse<String> assemblySnapshot = get(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-payments/snapshot");
            assertThat(assemblySnapshot.statusCode()).isEqualTo(200);
            assertThat(assemblySnapshot.body())
                    .contains("\"tenantId\":\"tenant-a\"")
                    .contains("\"assemblyId\":\"assembly-payments\"")
                    .contains("\"layout\"");
            HttpResponse<String> assemblyLoaded = post(
                    server,
                    "/studio/tenants/tenant-b/assemblies/assembly-import/snapshot",
                    assemblySnapshot.body());
            assertThat(assemblyLoaded.statusCode()).isEqualTo(200);
            assertThat(assemblyLoaded.body())
                    .contains("\"tenantId\":\"tenant-b\"")
                    .contains("\"assemblyId\":\"assembly-import\"")
                    .contains("\"selectedSurface\":\"payment\"");

            HttpResponse<String> assemblies = get(server, "/studio/tenants/tenant-a/assemblies");
            assertThat(assemblies.statusCode()).isEqualTo(200);
            assertThat(assemblies.body()).contains("assembly-demo", "assembly-payments");

            HttpResponse<String> projection = get(server, "/studio/tenants/tenant-a/assemblies/assembly-payments/dynamic-dcp");
            assertThat(projection.statusCode()).isEqualTo(200);
            assertThat(projection.body())
                    .contains("\"compositionMode\":\"DYNAMIC\"")
                    .contains("\"level\":\"PARENT\"")
                    .contains("\"level\":\"ASSEMBLY\"")
                    .contains("\"level\":\"CHILD\"");

            HttpResponse<String> replacements = get(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-payments/dynamic-dcp/replacements?componentNodeId=component.validation-service");
            assertThat(replacements.statusCode()).isEqualTo(200);
            assertThat(replacements.body())
                    .contains("\"componentNodeId\":\"component.validation-service\"")
                    .contains("customer-policy-validator")
                    .contains("\"status\":\"BLOCKED\"");

            String uploadedJarName = "foundry-substrate-offers-0.1.0-SNAPSHOT.jar";
            HttpResponse<String> jarAdmission = post(server, "/studio/tenants/tenant-a/catalog/admissions", """
                    {
                      "assemblyId": "assembly-checkout",
                      "artifacts": [
                        {
                          "fileName": "%s",
                          "artifactBase64": "%s"
                        }
                      ]
                    }
                    """.formatted(
                            uploadedJarName,
                            jarBase64(manifest("foundry-substrate-offers", "provider.call"))));
            assertThat(jarAdmission.statusCode()).isEqualTo(200);
            assertThat(jarAdmission.body())
                    .contains("\"status\":\"VERIFIED\"")
                    .contains("\"catalogEntryId\":\"uploaded:" + uploadedJarName + "\"");

            HttpResponse<String> connections = get(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-checkout/dynamic-dcp/connection-candidates?catalogEntryId="
                            + URLEncoder.encode("uploaded:" + uploadedJarName, StandardCharsets.UTF_8));
            assertThat(connections.statusCode()).isEqualTo(200);
            assertThat(connections.body())
                    .contains("\"catalogEntryId\":\"uploaded:" + uploadedJarName + "\"")
                    .contains("\"connections\"")
                    .contains("\"replacements\"");
        }
    }

    /**
     * Regression test: the HTTP Dynamic DCP route honors sessionId so Studio Step
     * 10 renders the accepted draft inventory, not the whole catalog.
     */
    @Test
    void servesDraftScopedDynamicDcpProjectionWhenSessionIdIsSupplied() throws Exception {
        try (StudioServer server = started()) {
            HttpResponse<String> created = post(server, "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions", """
                    {
                      "baseCatalogHash": "sha256:catalog",
                      "needsId": "",
                      "initialCandidateId": "",
                      "collaboratorId": "alice",
                      "collaboratorName": "Alice"
                    }
                    """);
            assertThat(created.statusCode()).isEqualTo(200);
            StudioCreateDraftCompositionResponse createdBody = StudioJson.mapper()
                    .readValue(created.body(), StudioCreateDraftCompositionResponse.class);
            String sessionId = createdBody.session().sessionId();

            HttpResponse<String> intent = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions/" + sessionId + "/intents",
                    """
                    {
                      "tenantId": "tenant-a",
                      "assemblyId": "assembly-demo",
                      "sessionId": "%s",
                      "baseRevision": 0,
                      "type": "ADD_COMPONENT",
                      "collaboratorId": "alice",
                      "collaboratorName": "Alice",
                      "catalogEntryId": "com.unfurl:validation-service:1.1.0"
                    }
                    """.formatted(sessionId));
            assertThat(intent.statusCode()).isEqualTo(200);
            assertThat(intent.body()).contains("\"status\":\"VALID\"");

            HttpResponse<String> projection = get(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-demo/dynamic-dcp?sessionId=" + sessionId);

            assertThat(projection.statusCode()).isEqualTo(200);
            assertThat(projection.body())
                    .contains("\"catalogEntryId\":\"com.unfurl:validation-service:1.1.0\"")
                    .doesNotContain("\"catalogEntryId\":\"com.unfurl:storage-s3:1.2.0\"");
        }
    }

    @Test
    void servesCompiledExportArtifactContent() throws Exception {
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
        StudioIntentRequest intent = new StudioIntentRequest();
        intent.tenantId = "tenant-a";
        intent.assemblyId = "assembly-demo";
        intent.sessionId = created.session().sessionId();
        intent.baseRevision = 0;
        intent.type = "ADD_COMPONENT";
        intent.collaboratorId = "alice";
        intent.put("catalogEntryId", "com.unfurl:validation-service:1.1.0");
        service.applyIntent("tenant-a", "assembly-demo", created.session().sessionId(), intent);
        StudioCompileDraftCandidateResponse compiled = service.compileCandidate(
                "tenant-a",
                "assembly-demo",
                created.session().sessionId(),
                new StudioCompileDraftCandidateRequest(
                        "tenant-a",
                        "assembly-demo",
                        created.session().sessionId(),
                        1,
                        false,
                        null));
        assertThat(compiled.status())
                .as(compiled.reason() + ": " + compiled.details())
                .isEqualTo("COMPILED");

        try (StudioServer server = started(service)) {
            HttpResponse<String> artifact = get(server, compiled.contractArtifact().url());

            assertThat(artifact.statusCode()).isEqualTo(200);
            assertThat(artifact.headers().firstValue("Content-Type")).contains("application/yaml");
            assertThat(artifact.body()).contains("validation-service");
        }
    }

    @Test
    void servesCollaborativeSessionsAndVerifiedAssetContent(@TempDir Path dir) throws Exception {
        Path asset = dir.resolve("META-INF/visual/validation-service.glb");
        Files.createDirectories(asset.getParent());
        Files.writeString(
                asset,
                "asset:validation-service:META-INF/visual/validation-service.glb",
                StandardCharsets.UTF_8);

        try (StudioServer server = started(new StudioCatalogService(null, dir))) {
            HttpResponse<String> created = post(server, "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions", """
                    {
                      "baseCatalogHash": "sha256:catalog",
                      "needsId": "needs-checkout",
                      "initialCandidateId": "cand-initial",
                      "collaboratorId": "alice",
                      "collaboratorName": "Alice"
                    }
                    """);
            assertThat(created.statusCode()).isEqualTo(200);
            StudioCreateDraftCompositionResponse createdBody = StudioJson.mapper()
                    .readValue(created.body(), StudioCreateDraftCompositionResponse.class);

            HttpResponse<String> intent = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions/"
                            + createdBody.session().sessionId()
                            + "/intents",
                    """
                    {
                      "tenantId": "tenant-a",
                      "assemblyId": "assembly-demo",
                      "sessionId": "%s",
                      "baseRevision": 0,
                      "type": "REPLACE_COMPONENT",
                      "collaboratorId": "bob",
                      "collaboratorName": "Bob",
                      "newCatalogEntryId": "com.unfurl:validation-service:1.1.0"
                    }
                    """.formatted(createdBody.session().sessionId()));
            assertThat(intent.statusCode()).isEqualTo(200);
            assertThat(intent.body())
                    .contains("\"status\":\"VALID\"")
                    .contains("\"newRevision\":1")
                    .contains("\"collaboratorId\":\"bob\"");

            HttpResponse<String> stale = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions/"
                            + createdBody.session().sessionId()
                            + "/intents",
                    """
                    {
                      "tenantId": "tenant-a",
                      "assemblyId": "assembly-demo",
                      "sessionId": "%s",
                      "baseRevision": 0,
                      "type": "CONNECT",
                      "collaboratorId": "alice"
                    }
                    """.formatted(createdBody.session().sessionId()));
            assertThat(stale.body())
                    .contains("\"status\":\"STALE_REVISION\"")
                    .contains("\"expectedRevision\":1")
                    .contains("\"receivedRevision\":0");

            HttpResponse<String> events = get(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions/"
                            + createdBody.session().sessionId()
                            + "/events?once=true");
            assertThat(events.statusCode()).isEqualTo(200);
            assertThat(events.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(contentType -> assertThat(contentType).contains("text/event-stream"));
            assertThat(events.body())
                    .contains("retry: 3000")
                    .contains("event: session")
                    .contains("\"eventId\":\"" + createdBody.session().sessionId() + ":1\"")
                    .contains("\"sceneRevision\":1");

            HttpResponse<String> metadata = get(server, "/studio/tenants/tenant-a/assets/validation-service-model");
            StudioVisualAsset visualAsset = StudioJson.mapper().readValue(metadata.body(), StudioVisualAsset.class);
            HttpResponse<byte[]> content = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri(server, "/studio/tenants/tenant-a/assets/validation-service-model/content?sha256=" + visualAsset.sha256()))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(content.statusCode()).isEqualTo(200);
            assertThat(content.headers().firstValue("Content-Type")).contains("model/gltf-binary");
            assertThat(new String(content.body(), StandardCharsets.UTF_8))
                    .isEqualTo("asset:validation-service:META-INF/visual/validation-service.glb");
        }
    }

    /**
     * Regression test: keeps a live session event stream open while sending other tenant
     * requests, proving the server executor prevents SSE from starving intent/catalog work.
     */
    @Test
    void liveSessionEventsDoNotBlockConcurrentTenantRequests() throws Exception {
        try (StudioServer server = started()) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> created = post(server, "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions", """
                    {
                      "baseCatalogHash": "sha256:catalog",
                      "needsId": "needs-checkout"
                    }
                    """);
            StudioCreateDraftCompositionResponse createdBody = StudioJson.mapper()
                    .readValue(created.body(), StudioCreateDraftCompositionResponse.class);
            String sessionPath = "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions/"
                    + createdBody.session().sessionId();
            var eventsFuture = client.sendAsync(
                    HttpRequest.newBuilder(uri(server, sessionPath + "/events"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> events = eventsFuture.get(5, TimeUnit.SECONDS);
            assertThat(events.statusCode()).isEqualTo(200);
            assertThat(events.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(contentType -> assertThat(contentType).contains("text/event-stream"));

            try (InputStream ignored = events.body()) {
                HttpResponse<String> intent = client.send(
                        HttpRequest.newBuilder(uri(server, sessionPath + "/intents"))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("""
                                        {
                                          "tenantId": "tenant-a",
                                          "assemblyId": "assembly-demo",
                                          "sessionId": "%s",
                                          "baseRevision": 0,
                                          "type": "ADD_COMPONENT",
                                          "catalogEntryId": "com.unfurl:validation-service:1.1.0"
                                        }
                                        """.formatted(createdBody.session().sessionId())))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(intent.statusCode()).isEqualTo(200);
                assertThat(intent.body())
                        .contains("\"status\":\"VALID\"")
                        .contains("\"newRevision\":1");

                HttpResponse<String> candidates = client.send(
                        HttpRequest.newBuilder(uri(
                                        server,
                                        "/studio/tenants/tenant-a/assemblies/assembly-demo/dynamic-dcp/connection-candidates"
                                                + "?catalogEntryId=com.unfurl%3Avalidation-service%3A1.1.0"))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(candidates.statusCode()).isEqualTo(200);
                assertThat(candidates.body()).contains("\"catalogEntryId\":\"com.unfurl:validation-service:1.1.0\"");
            } finally {
                eventsFuture.cancel(true);
            }
        }
    }

    @Test
    void servesAuthoringConverseAndReturnedIntentAppliesThroughDraftPath() throws Exception {
        try (StudioServer server = started()) {
            HttpResponse<String> created = post(server, "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions", """
                    {
                      "baseCatalogHash": "sha256:catalog",
                      "needsId": "needs-checkout"
                    }
                    """);
            StudioCreateDraftCompositionResponse createdBody = StudioJson.mapper()
                    .readValue(created.body(), StudioCreateDraftCompositionResponse.class);

            HttpResponse<String> proposal = post(server, "/studio/authoring/converse", """
                    {
                      "tenantId": "tenant-a",
                      "assemblyId": "assembly-demo",
                      "sessionId": "%s",
                      "conversation": [],
                      "userMessage": "Build a validation service for checkout payments"
                    }
                    """.formatted(createdBody.session().sessionId()));

            assertThat(proposal.statusCode()).isEqualTo(200);
            assertThat(proposal.body())
                    .contains("\"kind\":\"proposal\"")
                    .contains("\"catalogEntryId\":\"com.unfurl:validation-service:1.1.0\"")
                    .doesNotContain("apiKey", "providerKey", "modelProvider");

            StudioAuthoringConverseResponse response = StudioJson.mapper()
                    .readValue(proposal.body(), StudioAuthoringConverseResponse.class);
            assertThat(response.proposal()).isNotNull();
            assertThat(response.proposal().intents()).hasSize(1);

            String applyBody = StudioJson.mapper().writeValueAsString(response.proposal().intents().get(0))
                    .replaceFirst("\\{", """
                            {
                              "tenantId": "tenant-a",
                              "assemblyId": "assembly-demo",
                              "sessionId": "%s",
                              "baseRevision": 0,
                            """.formatted(createdBody.session().sessionId()));
            HttpResponse<String> applied = post(
                    server,
                    "/studio/tenants/tenant-a/assemblies/assembly-demo/sessions/"
                            + createdBody.session().sessionId()
                            + "/intents",
                    applyBody);

            assertThat(applied.statusCode()).isEqualTo(200);
            assertThat(applied.body())
                    .contains("\"status\":\"VALID\"")
                    .contains("\"newRevision\":1");

            HttpResponse<String> clarify = post(server, "/studio/authoring/converse", """
                    {
                      "tenantId": "tenant-a",
                      "assemblyId": "assembly-demo",
                      "userMessage": "Build"
                    }
                    """);
            assertThat(clarify.body()).contains("\"kind\":\"clarify\"", "\"questions\"");

            HttpResponse<String> gap = post(server, "/studio/authoring/converse", """
                    {
                      "tenantId": "tenant-a",
                      "assemblyId": "assembly-demo",
                      "userMessage": "Build an uncatalogued capability"
                    }
                    """);
            assertThat(gap.body()).contains("\"kind\":\"gap\"", "uncatalogued.capability");
        }
    }

    /**
     * Regression test: Foundry HTTP tools can call Studio through the canonical
     * `/studio/tools/{toolName}` gateway and still hit the same governed Studio
     * service paths as the UI.
     */
    @Test
    void servesFoundryCompatibleStudioToolGateway() throws Exception {
        try (StudioServer server = started()) {
            HttpResponse<String> catalogGap = post(server, "/studio/tools/fabric.catalog-verify", """
                    {
                      "callId": "catalog-gap",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "requiredCapabilities": ["validate.payment", "missing.capability"]
                      }
                    }
                    """);
            assertThat(catalogGap.statusCode()).isEqualTo(200);
            assertThat(catalogGap.body())
                    .contains("\"success\":true")
                    .contains("\"status\":\"GAP\"")
                    .contains("missing.capability");

            HttpResponse<String> assembly = post(server, "/studio/tools/fabric.assembly-create", """
                    {
                      "callId": "assembly",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "request": {
                          "assemblyId": "assembly-tools",
                          "targetApplicationName": "Tool Gateway",
                          "defaultDeploymentTarget": "local-compose"
                        }
                      }
                    }
                    """);
            assertThat(assembly.statusCode()).isEqualTo(200);
            assertThat(assembly.body()).contains("\"status\":\"PASS\"", "assembly-tools");

            HttpResponse<String> session = post(server, "/studio/tools/fabric.session-start", """
                    {
                      "callId": "session",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "assemblyId": "assembly-tools",
                        "request": {
                          "baseCatalogHash": "sha256:catalog",
                          "needsId": "",
                          "collaboratorId": "alice",
                          "collaboratorName": "Alice"
                        }
                      }
                    }
                    """);
            assertThat(session.statusCode()).isEqualTo(200);
            StudioToolCallResult sessionResult = StudioJson.mapper()
                    .readValue(session.body(), StudioToolCallResult.class);
            String sessionId = sessionId(sessionResult);
            assertThat(sessionId).startsWith("studio-session-");

            HttpResponse<String> intent = post(server, "/studio/tools/fabric.session-intent-apply", """
                    {
                      "callId": "intent",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "assemblyId": "assembly-tools",
                        "sessionId": "%s",
                        "request": {
                          "tenantId": "tenant-a",
                          "assemblyId": "assembly-tools",
                          "sessionId": "%s",
                          "baseRevision": 0,
                          "type": "ADD_COMPONENT",
                          "collaboratorId": "alice",
                          "catalogEntryId": "com.unfurl:validation-service:1.1.0"
                        }
                      }
                    }
                    """.formatted(sessionId, sessionId));
            assertThat(intent.statusCode()).isEqualTo(200);
            assertThat(intent.body()).contains("\"status\":\"PASS\"", "\"status\":\"VALID\"");

            HttpResponse<String> projection = post(server, "/studio/tools/fabric.dynamic-dcp-project", """
                    {
                      "callId": "projection",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "assemblyId": "assembly-tools",
                        "sessionId": "%s"
                      }
                    }
                    """.formatted(sessionId));
            assertThat(projection.statusCode()).isEqualTo(200);
            assertThat(projection.body())
                    .contains("\"status\":\"PASS\"")
                    .contains("\"catalogEntryId\":\"com.unfurl:validation-service:1.1.0\"")
                    .doesNotContain("\"catalogEntryId\":\"com.unfurl:storage-s3:1.2.0\"");

            HttpResponse<String> compiled = post(server, "/studio/tools/fabric.candidate-compile", """
                    {
                      "callId": "compile",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "assemblyId": "assembly-tools",
                        "sessionId": "%s",
                        "request": {
                          "tenantId": "tenant-a",
                          "assemblyId": "assembly-tools",
                          "sessionId": "%s",
                          "expectedRevision": 1,
                          "sign": false
                        }
                      }
                    }
                    """.formatted(sessionId, sessionId));
            assertThat(compiled.statusCode()).isEqualTo(200);
            assertThat(compiled.body()).contains("\"status\":\"PASS\"", "\"status\":\"COMPILED\"");
            StudioToolCallResult compileResult = StudioJson.mapper()
                    .readValue(compiled.body(), StudioToolCallResult.class);
            Map<?, ?> contractArtifact = contractArtifact(compileResult);

            HttpResponse<String> downloaded = post(server, "/studio/tools/fabric.export-download", """
                    {
                      "callId": "download",
                      "arguments": {
                        "tenantId": "tenant-a",
                        "artifactId": "%s",
                        "sha256": "%s"
                      }
                    }
                    """.formatted(contractArtifact.get("artifactId"), contractArtifact.get("sha256")));
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body())
                    .contains("\"status\":\"PASS\"")
                    .contains("\"contentBase64\"")
                    .contains("\"sha256\":\"" + contractArtifact.get("sha256") + "\"");
        }
    }

    private StudioServer started() throws Exception {
        return started(new StudioCatalogService());
    }

    private StudioServer started(StudioCatalogService service) throws Exception {
        StudioServer server = new StudioServer("127.0.0.1", 0, service);
        server.start();
        return server;
    }

    private HttpResponse<String> get(StudioServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(server, path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(StudioServer server, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(server, path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * HTTP helper: sends a DELETE request to a running StudioServer route and
     * returns the string response body for route-contract assertions.
     */
    private HttpResponse<String> delete(StudioServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(server, path))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(StudioServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    /**
     * Test helper: extracts the nested session id from a generic Studio tool
     * result map produced by the gateway.
     */
    @SuppressWarnings("unchecked")
    private String sessionId(StudioToolCallResult result) {
        Map<String, Object> response = (Map<String, Object>) result.output().get("response");
        Map<String, Object> session = (Map<String, Object>) response.get("session");
        return String.valueOf(session.get("sessionId"));
    }

    /**
     * Test helper: extracts the compiled contract artifact from a generic Studio
     * tool result map for the export-download tool.
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> contractArtifact(StudioToolCallResult result) {
        Map<String, Object> response = (Map<String, Object>) result.output().get("response");
        return (Map<?, ?>) response.get("contractArtifact");
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static Path writeNeeds(Path dir, String capability) throws Exception {
        Path target = dir.resolve("needs.yaml");
        Files.writeString(target, """
                requiredCapabilities:
                  - capability: %s
                    capabilityVersion: ^1
                """.formatted(capability), StandardCharsets.UTF_8);
        return target;
    }

    private static void writeCatalogJar(Path dir, String fileName, String artifact, String capability) throws Exception {
        Files.createDirectories(dir);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifest(artifact, capability).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    /**
     * Fixture helper: builds an in-memory JAR upload body containing the DCP catalog
     * manifest Studio admission expects under META-INF.
     */
    private static String jarBase64(String manifestYaml) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static String manifest(String artifact, String capability) {
        return """
                claim:
                  identity:
                    uri: urn:unfurl:test:%s
                    name: %s
                    kind: COMPONENT
                    version: 1.0.0
                    publisher: Unfurl
                  domain:
                    summary: %s
                    concerns:
                      - concern: %s
                        description: %s
                    boundary_principles:
                      - test boundary
                  refusals:
                    - concern: unrelated.concern
                      rationale: This component deliberately owns only its declared capability.
                      owned_by: host
                  dependencies:
                    needs: []
                  offers:
                    - capability: %s
                      description: %s
                      consumer_access: ANY
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
                """.formatted(artifact, artifact, artifact, capability, capability, capability, capability, artifact);
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

    private static String jsonString(String value) throws Exception {
        return StudioJson.mapper().writeValueAsString(value);
    }

    /**
     * Fixture helper: lists ZIP entries from the binary claim-bundle response without
     * writing test artifacts to disk.
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
