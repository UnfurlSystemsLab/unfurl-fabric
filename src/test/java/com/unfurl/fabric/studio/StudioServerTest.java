package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
                        { "fileName": "payment.jar" }
                      ]
                    }
                    """);
            assertThat(admission.statusCode()).isEqualTo(200);
            assertThat(admission.body())
                    .contains("\"status\":\"VERIFIED\"")
                    .contains("\"catalogEntryId\":\"uploaded:payment.jar\"");

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
                    .contains("checkout.platform.run")
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

    private URI uri(StudioServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
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
                    boundaryPrinciples:
                      - test boundary
                  refusals: []
                  dependencies:
                    needs: []
                  offers:
                    - capability: %s
                      description: %s
                      consumerAccess: ANY
                      stability: STABLE
                      version: 1.0.0
                      metered: false
                catalog:
                  lifecycle:
                    status: ACTIVE
                  artifact:
                    coordinates: com.unfurl:%s:1.0.0
                    packaging: jar
                    source: catalog
                  binding:
                    defaultMode: IN_PROCESS
                    supportedModes: [IN_PROCESS]
                  componentShapeProfile:
                    defaultShape: IN_PROCESS_LIBRARY
                    supportedShapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                    shapeRuntime: {}
                """.formatted(artifact, artifact, artifact, capability, capability, capability, capability, artifact);
    }
}
