package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class StudioDeploymentServiceTest {

    @Test
    void resolvesDeploymentPolicyDraftIntoBindingPlan(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put", containerShapeProfile());
        Path needs = writeNeeds(dir, "storage.put");

        StudioDeploymentResolveResponse response = new StudioDeploymentService().resolveDeployment(
                new StudioDeploymentResolveRequest(
                        catalog,
                        needs,
                        null,
                        null,
                        false,
                        containerPolicy()));

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.candidateId()).startsWith("cand-");
        assertThat(response.selections())
                .singleElement()
                .satisfies(selection -> {
                    assertThat(selection.componentId()).isEqualTo("com.unfurl:storage-s3:1.0.0");
                    assertThat(selection.capability()).isEqualTo("storage.put");
                    assertThat(selection.deploymentShape().name()).isEqualTo("CONTAINERIZED_SERVICE");
                    assertThat(selection.requiredPorts()).contains("substrate.container.runtime");
                });
    }

    @Test
    void ambiguousCandidatesRequireSelection(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        writeCatalogJar(catalog, "storage-a.jar", "storage-a", "storage.put", containerShapeProfile());
        writeCatalogJar(catalog, "storage-b.jar", "storage-b", "storage.put", containerShapeProfile());
        Path needs = writeNeeds(dir, "storage.put");

        StudioDeploymentResolveResponse response = new StudioDeploymentService().resolveDeployment(
                new StudioDeploymentResolveRequest(
                        catalog,
                        needs,
                        null,
                        null,
                        false,
                        containerPolicy()));

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reason()).isEqualTo("AMBIGUOUS_CANDIDATES");
        assertThat(response.details()).contains("valid ids: cand-");
    }

    @Test
    void autoSelectBestResolvesAmbiguousCandidates(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        writeCatalogJar(catalog, "storage-a.jar", "storage-a", "storage.put", containerShapeProfile());
        writeCatalogJar(catalog, "storage-b.jar", "storage-b", "storage.put", containerShapeProfile());
        Path needs = writeNeeds(dir, "storage.put");

        StudioDeploymentResolveResponse response = new StudioDeploymentService().resolveDeployment(
                new StudioDeploymentResolveRequest(
                        catalog,
                        needs,
                        null,
                        null,
                        true,
                        containerPolicy()));

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.selections()).hasSize(1);
    }

    @Test
    void unknownCandidateIdListsValidIds(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        writeCatalogJar(catalog, "storage-a.jar", "storage-a", "storage.put", containerShapeProfile());
        writeCatalogJar(catalog, "storage-b.jar", "storage-b", "storage.put", containerShapeProfile());
        Path needs = writeNeeds(dir, "storage.put");

        StudioDeploymentResolveResponse response = new StudioDeploymentService().resolveDeployment(
                new StudioDeploymentResolveRequest(
                        catalog,
                        needs,
                        null,
                        "cand-does-not-exist",
                        false,
                        containerPolicy()));

        assertThat(response.status()).isEqualTo("INVALID");
        assertThat(response.reason()).isEqualTo("UNKNOWN_CANDIDATE_ID");
        assertThat(response.details()).contains("cand-does-not-exist").contains("valid ids: cand-");
    }

    private static StudioDeploymentPolicyDraft containerPolicy() {
        return new StudioDeploymentPolicyDraft(
                List.of("CONTAINERIZED_SERVICE"),
                List.of(),
                List.of(),
                new StudioDeploymentRuntimeDraft("21", true, true, true, null));
    }

    private static Path writeNeeds(Path dir, String capability) throws IOException {
        Path target = dir.resolve("needs.yaml");
        Files.writeString(target, """
                requiredCapabilities:
                  - capability: %s
                    capabilityVersion: ^1
                """.formatted(capability), StandardCharsets.UTF_8);
        return target;
    }

    private static void writeCatalogJar(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            String componentShapeProfileYaml) throws IOException {
        Files.createDirectories(dir);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifest(artifact, capability, componentShapeProfileYaml).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static String manifest(String artifact, String capability, String componentShapeProfileYaml) {
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
                  refusals: []
                  dependencies:
                    needs: []
                  offers:
                    - capability: %s
                      description: %s
                      consumer_access: ANY
                      stability: STABLE
                      version: 1.0.0
                      metered: false
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
                %s
                """.formatted(artifact, artifact, artifact, capability, capability,
                capability, capability, artifact, componentShapeProfileYaml);
    }

    private static String containerShapeProfile() {
        return """
                  component_shape_profile:
                    default_shape: IN_PROCESS_LIBRARY
                    supported_shapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                    shape_runtime: {}
                """;
    }
}
