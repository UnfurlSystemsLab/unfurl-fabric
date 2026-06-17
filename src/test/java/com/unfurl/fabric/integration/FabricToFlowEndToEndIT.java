package com.unfurl.fabric.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.fabric.cli.FabricCli;
import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FabricToFlowEndToEndIT {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    @TempDir
    Path tempDir;

    // flow.log lives OUTSIDE @TempDir: the spawned flow process can hold its handle
    // briefly after exit on Windows, which would race @TempDir cleanup.
    private Path flowLog;

    @Test
    void fabricSignedContractAndSubstrateProfileBootFlowInStrictMode() throws Exception {
        Path flowJar = flowJar();
        Assumptions.assumeTrue(Files.isRegularFile(flowJar),
                "set -Dunfurl.flow.jar=<path-to-unfurl-flow-shaded-jar> or run mvn package in ../unfurl-flow");

        Path catalog = tempDir.resolve("catalog");
        writeCatalogJar(catalog, "function.jar", "function-local", "function.local",
                "function.local@*?substrate=true&provider=flow");
        Path needs = writeNeeds("function.local");
        Path compiled = tempDir.resolve("compiled.yaml");
        Path substrateProfile = tempDir.resolve("compiled.substrate-profile.yaml");

        assertRunOk(FabricCli.run(new String[]{"compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", compiled.toString(),
                "--substrate-profile-out", substrateProfile.toString()
        }, stdout(), stderr()));

        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(tempDir, "fabric-key.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(tempDir, "fabric-pub.pem", pair.getPublic());
        Path signed = tempDir.resolve("signed.yaml");
        assertRunOk(FabricCli.run(new String[]{"sign",
                "--contract", compiled.toString(),
                "--key", privateKey.toString(),
                "--public-key", publicKey.toString(),
                "--out", signed.toString()
        }, stdout(), stderr()));

        Path runtimeProfile = writeRuntimeProfile(signed, substrateProfile);
        int port = freePort();
        Process flow = startFlow(flowJar, runtimeProfile, port);
        try {
            waitForHealth(port);

            String claim = get(port, "/claim");
            assertThat(claim).contains("workflow.execute");

            Map<String, Object> components = readMap(get(port, "/components"));
            assertThat(components.get("registeredCapabilities")).isEqualTo(List.of("function.local"));

            HttpResponse<String> defined = post(port, "/workflows", workflow());
            assertThat(defined.statusCode()).as(defined.body()).isEqualTo(200);
            HttpResponse<String> execution = post(port, "/executions", Map.of(
                    "workflowId", "fabric-flow",
                    "workflowVersion", "1.0.0",
                    "input", Map.of()));
            assertThat(execution.statusCode()).isEqualTo(200);
            assertThat(execution.body()).contains("\"status\":\"COMPLETED\"", "from-fabric");
        } finally {
            flow.destroy();
            if (!flow.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                flow.destroyForcibly();
                flow.waitFor();
            }
        }
    }

    @Test
    void fabricContainerPolicyIsRejectedByFlowStrictShapeGate() throws Exception {
        Path flowJar = flowJar();
        Assumptions.assumeTrue(Files.isRegularFile(flowJar),
                "set -Dunfurl.flow.jar=<path-to-unfurl-flow-shaded-jar> or run mvn package in ../unfurl-flow");

        Path catalog = tempDir.resolve("shape-catalog");
        writeShapedCatalogJar(catalog, "function.jar", "function-local", "function.local",
                "function.local@*?substrate=true&provider=flow");
        Path needs = writeNeeds("function.local");
        Path policy = writeDeploymentPolicy();
        Path compiled = tempDir.resolve("shape-compiled.yaml");
        Path substrateProfile = tempDir.resolve("shape-compiled.substrate-profile.yaml");

        assertRunOk(FabricCli.run(new String[]{"compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString(),
                "--out", compiled.toString(),
                "--substrate-profile-out", substrateProfile.toString()
        }, stdout(), stderr()));

        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(tempDir, "shape-fabric-key.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(tempDir, "shape-fabric-pub.pem", pair.getPublic());
        Path signed = tempDir.resolve("shape-signed.yaml");
        assertRunOk(FabricCli.run(new String[]{"sign",
                "--contract", compiled.toString(),
                "--key", privateKey.toString(),
                "--public-key", publicKey.toString(),
                "--out", signed.toString()
        }, stdout(), stderr()));

        Path runtimeProfile = writeRuntimeProfile(signed, substrateProfile);
        int port = freePort();
        Process flow = startFlow(flowJar, runtimeProfile, port);
        try {
            assertThat(flow.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(flow.exitValue()).isNotZero();
            assertThat(Files.readString(flowLog, StandardCharsets.UTF_8))
                    .contains("deployment shape is not supported: CONTAINERIZED_SERVICE");
        } finally {
            flow.destroyForcibly();
            flow.waitFor();
        }
    }

    private Process startFlow(Path flowJar, Path runtimeProfile, int port) throws IOException {
        flowLog = Files.createTempFile("unfurl-flow-", ".log");
        return new ProcessBuilder(
                javaExecutable().toString(),
                "-Dunfurl.flow.port=" + port,
                "-jar", flowJar.toString(),
                "--profile", runtimeProfile.toString())
                .redirectErrorStream(true)
                .redirectOutput(flowLog.toFile())
                .start();
    }

    private void waitForHealth(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(port, "/health"))
                                .timeout(Duration.ofSeconds(1))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException ignored) {
                // server is still starting
            }
            Thread.sleep(100);
        }
        throw new AssertionError("flow did not become healthy; log:\n"
                + Files.readString(flowLog, StandardCharsets.UTF_8));
    }

    private String get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(port, path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> post(int port, String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(port, path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Map<String, Object> readMap(String body) throws IOException {
        return mapper.readValue(body, new TypeReference<>() {
        });
    }

    private Path writeRuntimeProfile(Path signed, Path substrateProfile) throws IOException {
        Path path = tempDir.resolve("runtime-profile.yaml");
        Files.writeString(path, """
                name: fabric-e2e
                engine: EMBEDDED
                stateStore: IN_MEMORY
                eventSink: IN_MEMORY
                enabledComponents:
                  - function.local
                  - storage
                componentConfig: {}
                triggerConfig: {}
                substrateProfileMode: STRICT
                fabricContractPath: "%s"
                substrateProfilePath: "%s"
                """.formatted(yamlPath(signed), yamlPath(substrateProfile)), StandardCharsets.UTF_8);
        return path;
    }

    private Map<String, Object> workflow() {
        return Map.of(
                "id", "fabric-flow",
                "version", "1.0.0",
                "inputSchema", Map.of(),
                "outputSchema", Map.of(),
                "triggers", List.of(),
                "nodes", List.of(node()),
                "edges", List.of());
    }

    private Map<String, Object> node() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "echo");
        node.put("type", "ACTION");
        node.put("uses", "function.local");
        node.put("config", Map.of("functionName", "echo"));
        node.put("input", Map.of("message", "from-fabric"));
        node.put("outputMapping", Map.of());
        node.put("dependencies", List.of());
        node.put("onFailure", "FAIL");
        node.put("timeout", "PT2S");
        node.put("template", false);
        return node;
    }

    private void writeCatalogJar(Path dir, String fileName, String artifact, String capability, String dependency) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifest(artifact, capability, dependency).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private void writeShapedCatalogJar(Path dir, String fileName, String artifact, String capability, String dependency) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(shapedManifest(artifact, capability, dependency).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private String manifest(String artifact, String capability, String dependency) {
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
                    needs:
                      - %s
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
                """.formatted(artifact, artifact, artifact, capability, capability, dependency, capability, capability, artifact);
    }

    private String shapedManifest(String artifact, String capability, String dependency) {
        return manifest(artifact, capability, dependency) + """
                  componentShapeProfile:
                    defaultShape: IN_PROCESS_LIBRARY
                    supportedShapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                    shapeRuntime: {}
                """;
    }

    private Path writeNeeds(String capability) throws IOException {
        Path target = tempDir.resolve("needs.yaml");
        Files.writeString(target, """
                requiredCapabilities:
                  - capability: %s
                    capabilityVersion: ^1
                """.formatted(capability), StandardCharsets.UTF_8);
        return target;
    }

    private Path writeDeploymentPolicy() throws IOException {
        Path target = tempDir.resolve("deployment-policy.yaml");
        Files.writeString(target, """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """, StandardCharsets.UTF_8);
        return target;
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Path flowJar() {
        String configured = System.getProperty("unfurl.flow.jar");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("..", "unfurl-flow", "target", "unfurl-flow-0.1.0-SNAPSHOT.jar").normalize();
    }

    private Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String yamlPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private PrintStream stdout() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private PrintStream stderr() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private void assertRunOk(int exitCode) {
        assertThat(exitCode).isEqualTo(0);
    }
}
