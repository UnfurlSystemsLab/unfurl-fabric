package com.unfurl.fabric.cli;

import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliDeploymentDiagnosticsTest {

    @Test
    void explainsDeploymentPlanFromSignedContract(@TempDir Path dir) throws Exception {
        SignedContract signed = compileAndSign(dir, null);

        CliTestFixtures.CliRun result = CliTestFixtures.run("explain-deployment",
                "--contract", signed.path().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("Fabric deployment explanation")
                .contains("bindingPlanEntries=1")
                .contains("deploymentShape=IN_PROCESS_LIBRARY")
                .contains("requiredSubstratePorts=[]");
    }

    @Test
    void resolvesDeploymentPlanWithoutCompilingContract(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir, containerShapeProfile());
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path policy = writePolicy(dir, """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);

        CliTestFixtures.CliRun result = CliTestFixtures.run("resolve-deployment",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("Deployment resolution")
                .contains("selectedShapePerComponent={com.unfurl:storage-s3:1.0.0=CONTAINERIZED_SERVICE}")
                .contains("bindingPlan:")
                .contains("substrate.container.runtime");
    }

    @Test
    void resolveDeploymentFailureRendersRejectedShapes(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir, containerShapeProfile());
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path policy = writePolicy(dir, """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);

        CliTestFixtures.CliRun result = CliTestFixtures.run("resolve-deployment",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr())
                .contains("deployment shape resolution failed")
                .contains("rejectedShapes")
                .contains("CONTAINERIZED_SERVICE");
    }

    @Test
    void diffHighlightsDeploymentShapeChanges(@TempDir Path dir) throws Exception {
        SignedContract left = compileAndSign(dir.resolve("left"), null);
        Path policy = writePolicy(dir.resolve("right"), """
                preferredShapes: [CONTAINERIZED_SERVICE]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);
        SignedContract right = compileAndSign(dir.resolve("right"), policy);

        CliTestFixtures.CliRun result = CliTestFixtures.run("diff",
                "--left", left.path().toString(),
                "--right", right.path().toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("Deployment shape delta")
                .contains("changedDeploymentShapes:")
                .contains("IN_PROCESS_LIBRARY -> CONTAINERIZED_SERVICE")
                .contains("substrate.container.runtime");
    }

    private static SignedContract compileAndSign(Path dir, Path deploymentPolicy) throws Exception {
        Files.createDirectories(dir);
        Path catalog = catalog(dir, containerShapeProfile());
        Path needs = CliTestFixtures.writeNeeds(dir, "storage.put");
        Path compiled = dir.resolve("contract.yaml");
        java.util.List<String> compileArgs = new java.util.ArrayList<>(java.util.List.of(
                "compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--out", compiled.toString()));
        if (deploymentPolicy != null) {
            compileArgs.add("--deployment-policy");
            compileArgs.add(deploymentPolicy.toString());
        }
        CliTestFixtures.CliRun compile = CliTestFixtures.run(compileArgs.toArray(String[]::new));
        assertThat(compile.exitCode()).as(compile.stderr()).isEqualTo(0);

        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(dir, "key.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(dir, "pub.pem", pair.getPublic());
        Path signed = dir.resolve("signed.yaml");
        CliTestFixtures.CliRun sign = CliTestFixtures.run("sign",
                "--contract", compiled.toString(),
                "--key", privateKey.toString(),
                "--public-key", publicKey.toString(),
                "--out", signed.toString());
        assertThat(sign.exitCode()).as(sign.stderr()).isEqualTo(0);
        return new SignedContract(signed);
    }

    private static Path catalog(Path dir, String shapeProfile) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeCatalogJarWithShapeProfile(
                catalog, "storage.jar", "storage-s3", "storage.put", shapeProfile);
        return catalog;
    }

    private static Path writePolicy(Path dir, String yaml) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve("deployment-policy.yaml");
        Files.writeString(path, yaml);
        return path;
    }

    private static String containerShapeProfile() {
        return """
                  componentShapeProfile:
                    defaultShape: IN_PROCESS_LIBRARY
                    supportedShapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                    shapeRuntime: {}
                """;
    }

    private record SignedContract(Path path) {
    }
}
