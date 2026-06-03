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
            assertThat(health.body()).contains("\"UP\"", "unfurl-fabric-studio");

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

    private StudioServer started() throws Exception {
        StudioServer server = new StudioServer("127.0.0.1", 0);
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
