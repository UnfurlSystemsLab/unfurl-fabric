package com.unfurl.fabric.cli;

import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliCompileDeploymentShapeTest {

    @Test
    void compileEmbedsInlineBindingPlan(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithShapeProfile(
                catalog, "storage.jar", "storage-s3", "storage.put", containerShapeProfile());
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path contractOut = dir.resolve("contract.yaml");
        Path policy = writePolicy(dir, """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString(),
                "--out", contractOut.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        CompiledContract compiled = CliTestFixtures.readCompiled(contractOut);
        assertThat(compiled.bindingPlan()).isNotNull();
        assertThat(compiled.bindingPlan().entries())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.deploymentShape()).isEqualTo(DeploymentShape.CONTAINERIZED_SERVICE);
                    assertThat(entry.requiredSubstratePorts())
                            .containsExactly("substrate.container.runtime", "substrate.http.client",
                                    "substrate.endpoint.discovery");
                });
        assertThat(compiled.selections())
                .singleElement()
                .satisfies(selection -> assertThat(selection.deploymentShape())
                        .isEqualTo(DeploymentShape.CONTAINERIZED_SERVICE));
    }

    @Test
    void compileWithoutDeploymentPolicyUsesDefaultInProcessPolicy(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path contractOut = dir.resolve("contract.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", contractOut.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        CompiledContract compiled = CliTestFixtures.readCompiled(contractOut);
        assertThat(compiled.bindingPlan().entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.deploymentShape())
                        .isEqualTo(DeploymentShape.IN_PROCESS_LIBRARY));
    }

    @Test
    void substrateProfilePortsReflectResolvedDeploymentShape(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithShapeProfile(
                catalog, "storage.jar", "storage-s3", "storage.put", containerShapeProfile());
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path contractOut = dir.resolve("contract.yaml");
        Path profileOut = dir.resolve("profile.yaml");
        Path policy = writePolicy(dir, """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString(),
                "--out", contractOut.toString(),
                "--substrate-profile-out", profileOut.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        SubstrateProfile profile = new SubstrateProfileCodec().parse(Files.readAllBytes(profileOut));
        assertThat(profile.portRequirements())
                .extracting(p -> p.port())
                .containsExactly("substrate.container.runtime", "substrate.endpoint.discovery",
                        "substrate.http.client");
    }

    @Test
    void resolutionFailureSurfacesRejectedShapeReport(@TempDir Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithShapeProfile(
                catalog, "storage.jar", "storage-s3", "storage.put", containerShapeProfile());
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path policy = writePolicy(dir, """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: [CONTAINERIZED_SERVICE]
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);

        CliTestFixtures.CliRun result = CliTestFixtures.run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString(),
                "--out", dir.resolve("contract.yaml").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr())
                .contains("deployment shape resolution failed")
                .contains("rejectedShapes")
                .contains("CONTAINERIZED_SERVICE")
                .contains("DeploymentPolicy.disallowedShapes contains CONTAINERIZED_SERVICE");
    }

    private static Path writePolicy(Path dir, String yaml) throws Exception {
        Path path = dir.resolve("deployment-policy.yaml");
        Files.writeString(path, yaml);
        return path;
    }

    private static String containerShapeProfile() {
        return """
                  componentShapeProfile:
                    defaultShape: CONTAINERIZED_SERVICE
                    supportedShapes: [CONTAINERIZED_SERVICE]
                    shapeRuntime: {}
                """;
    }
}
