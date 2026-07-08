package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.signing.SigningTestFixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class CliTestFixtures {
    static CliRun run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = FabricCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new CliRun(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Path writeCatalogJar(Path dir, String fileName, String artifact, String capability) throws IOException {
        return writeCatalogJar(dir, fileName, artifact, capability, "Unfurl", "ACTIVE");
    }

    static Path writeCatalogJar(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            String publisher,
            String lifecycle) throws IOException {
        return writeCatalogJar(dir, fileName, artifact, capability, publisher, lifecycle, "");
    }

    static Path writeCatalogJarWithDependencies(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            String... dependencies) throws IOException {
        String dependencyYaml = java.util.Arrays.stream(dependencies)
                .map(dep -> "                      - " + dep)
                .collect(java.util.stream.Collectors.joining("\n"));
        return writeCatalogJar(dir, fileName, artifact, capability, "Unfurl", "ACTIVE", dependencyYaml);
    }

    static Path writeCatalogJarWithShapeProfile(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            String componentShapeProfileYaml,
            String... dependencies) throws IOException {
        String dependencyYaml = java.util.Arrays.stream(dependencies)
                .map(dep -> "                      - " + dep)
                .collect(java.util.stream.Collectors.joining("\n"));
        return writeCatalogJar(dir, fileName, artifact, capability, "Unfurl", "ACTIVE",
                dependencyYaml, componentShapeProfileYaml);
    }

    private static Path writeCatalogJar(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            String publisher,
            String lifecycle,
            String dependencyYaml) throws IOException {
        return writeCatalogJar(dir, fileName, artifact, capability, publisher, lifecycle, dependencyYaml, "");
    }

    private static Path writeCatalogJar(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            String publisher,
            String lifecycle,
            String dependencyYaml,
            String componentShapeProfileYaml) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifest(artifact, capability, publisher, lifecycle, dependencyYaml, componentShapeProfileYaml)
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return target;
    }

    static Path writeNeeds(Path dir, String capability) throws IOException {
        Path target = dir.resolve("needs.yaml");
        Files.writeString(target, """
                requiredCapabilities:
                  - capability: %s
                    capabilityVersion: ^1
                """.formatted(capability), StandardCharsets.UTF_8);
        return target;
    }

    static Path writeTrustPolicy(Path dir, String trustedVendor) throws IOException {
        Path target = dir.resolve("trust.yaml");
        Files.writeString(target, """
                trustedVendors: [%s]
                minimumStability: EXPERIMENTAL
                allowedCapabilityPatterns: ["*"]
                allowedLifecycleStatuses: [ACTIVE]
                """.formatted(trustedVendor), StandardCharsets.UTF_8);
        return target;
    }

    static SignedPaths compileAndSign(Path dir) throws IOException {
        Path catalog = dir.resolve("catalog");
        writeCatalogJar(catalog, "storage.jar", "storage-s3", "storage.put");
        Path needs = writeNeeds(dir, "storage.put");
        Path compiled = dir.resolve("compiled.yaml");
        CliRun compile = run("compile", "--catalog", catalog.toString(), "--needs", needs.toString(),
                "--out", compiled.toString());
        if (compile.exitCode() != 0) {
            throw new AssertionError(compile.stderr());
        }

        return sign(dir, catalog, needs, compiled);
    }

    static SignedPaths compileAndSignShape(Path dir, String shape) throws IOException {
        Path catalog = dir.resolve("catalog-" + shape.toLowerCase(java.util.Locale.ROOT));
        writeCatalogJarWithShapeProfile(
                catalog,
                "storage.jar",
                "storage-" + shape.toLowerCase(java.util.Locale.ROOT),
                "storage.put",
                """
                          component_shape_profile:
                            default_shape: %s
                            supported_shapes: [%s]
                            shape_runtime: {}
                        """.formatted(shape, shape));
        Path needs = writeNeeds(dir, "storage.put");
        Path policy = dir.resolve("deployment-policy-" + shape.toLowerCase(java.util.Locale.ROOT) + ".yaml");
        Files.writeString(policy, """
                preferredShapes: [%s]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """.formatted(shape), StandardCharsets.UTF_8);
        Path compiled = dir.resolve("compiled-" + shape.toLowerCase(java.util.Locale.ROOT) + ".yaml");
        CliRun compile = run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString(),
                "--out", compiled.toString());
        if (compile.exitCode() != 0) {
            throw new AssertionError(compile.stderr());
        }
        return sign(dir, catalog, needs, compiled);
    }

    static SignedPaths compileAndSignMixedShapes(Path dir) throws IOException {
        Path catalog = dir.resolve("catalog-mixed");
        writeCatalogJarWithShapeProfile(catalog, "local.jar", "local-lib", "local.cap",
                shapeProfile("IN_PROCESS_LIBRARY"));
        writeCatalogJarWithShapeProfile(catalog, "api.jar", "api-service", "api.cap",
                shapeProfile("CONTAINERIZED_SERVICE"));
        writeCatalogJarWithShapeProfile(catalog, "adapter.jar", "remote-adapter", "remote.cap",
                shapeProfile("MANAGED_EXTERNAL_ADAPTER"));
        Path needs = dir.resolve("needs-mixed.yaml");
        Files.writeString(needs, """
                requiredCapabilities:
                  - capability: local.cap
                    capabilityVersion: ^1
                  - capability: api.cap
                    capabilityVersion: ^1
                  - capability: remote.cap
                    capabilityVersion: ^1
                """, StandardCharsets.UTF_8);
        Path policy = dir.resolve("deployment-policy-mixed.yaml");
        Files.writeString(policy, """
                preferredShapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE, MANAGED_EXTERNAL_ADAPTER]
                disallowedShapes: []
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """, StandardCharsets.UTF_8);
        Path compiled = dir.resolve("compiled-mixed.yaml");
        CliRun compile = run("compile",
                "--catalog", catalog.toString(),
                "--needs", needs.toString(),
                "--deployment-policy", policy.toString(),
                "--out", compiled.toString());
        if (compile.exitCode() != 0) {
            throw new AssertionError(compile.stderr());
        }
        return sign(dir, catalog, needs, compiled);
    }

    private static String shapeProfile(String shape) {
        return """
                  component_shape_profile:
                    default_shape: %s
                    supported_shapes: [%s]
                    shape_runtime: {}
                """.formatted(shape, shape);
    }

    private static SignedPaths sign(Path dir, Path catalog, Path needs, Path compiled) throws IOException {
        KeyPair pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(dir, "key.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(dir, "pub.pem", pair.getPublic());
        Path signed = dir.resolve("signed-" + compiled.getFileName());
        CliRun sign = run("sign", "--contract", compiled.toString(), "--key", privateKey.toString(),
                "--public-key", publicKey.toString(), "--out", signed.toString());
        if (sign.exitCode() != 0) {
            throw new AssertionError(sign.stderr());
        }
        Path trustKeys = dir.resolve("trust-keys");
        Files.createDirectories(trustKeys);
        SigningTestFixtures.writePublicKeyPem(trustKeys, "pub.pem", pair.getPublic());
        return new SignedPaths(catalog, needs, compiled, CliSupport.defaultProfilePath(compiled), signed, trustKeys);
    }

    static CompiledContract readCompiled(Path path) throws IOException {
        return new CompiledContractCodec().parse(Files.readAllBytes(path));
    }

    private static String manifest(
            String artifact,
            String capability,
            String publisher,
            String lifecycle,
            String dependencyYaml) {
        return manifest(artifact, capability, publisher, lifecycle, dependencyYaml, "");
    }

    private static String manifest(
            String artifact,
            String capability,
            String publisher,
            String lifecycle,
            String dependencyYaml,
            String componentShapeProfileYaml) {
        String deps = dependencyYaml == null || dependencyYaml.isBlank() ? "[]" : "\n" + dependencyYaml;
        String shapeProfile = componentShapeProfileYaml == null || componentShapeProfileYaml.isBlank()
                ? ""
                : "\n" + componentShapeProfileYaml;
        return """
                claim:
                  identity:
                    uri: urn:unfurl:test:%s
                    name: %s
                    kind: COMPONENT
                    version: 1.0.0
                    publisher: %s
                  domain:
                    summary: %s
                    concerns:
                      - concern: %s
                        description: %s
                    boundary_principles:
                      - test boundary
                  refusals: []
                  dependencies:
                    needs: %s
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
                    status: %s
                  artifact:
                    coordinates: com.unfurl:%s:1.0.0
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: IN_PROCESS
                    supported_modes: [IN_PROCESS]
                %s
                """.formatted(artifact, artifact, publisher, artifact, capability, capability,
                deps, capability, capability, lifecycle, artifact, shapeProfile);
    }

    record CliRun(int exitCode, String stdout, String stderr) {
    }

    record SignedPaths(Path catalog, Path needs, Path compiled, Path profile, Path signed, Path trustKeys) {
    }

    private CliTestFixtures() {
    }
}
