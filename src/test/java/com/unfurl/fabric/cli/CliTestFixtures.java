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

    /** Dependency the real Foundry claim declares on identity: it verifies tokens, so it is a protected service. */
    static final String FOUNDRY_IDENTITY_DEPENDENCY =
            "identity-context@v1?transport=in-process&shape=com.unfurl.substrate.policy.ExecutionContext&owner=customer-idp";

    /** Need the real Flow claim declares: the signed contract Fabric produces. */
    static final String FLOW_CONTRACT_DEPENDENCY = "workflow.contract.signed@v1?owner=fabric";

    /** Optional dependency the real Flow claim declares on Foundry: Flow runs workflows without it (DCP HLD-C v0.2 §3.4). */
    static final String FLOW_AGENT_DEPENDENCY =
            "agent.run@v1?transport=dcp&shape=com.unfurl.substrate.composition.ContractInvocation";

    /**
     * Fixture: a Foundry stand-in offering {@code agent.run} whose claim depends on {@code identity-context} as the real
     * Foundry claim does, so a composition with the identity provider records it as a protected service (HLD-needs-driven-
     * identity §5). The dependency is externally owned, so compositions without identity still compile. Like the real
     * claim it declares no dependency on Flow: Flow depends on Foundry, never the reverse.
     */
    static Path writeFoundryJar(Path dir, String fileName) throws IOException {
        return writeCatalogJarWithOptionalDependencies(dir, fileName, "agent.run", "agent.run",
                java.util.List.of(FOUNDRY_IDENTITY_DEPENDENCY), java.util.List.of());
    }

    /**
     * Fixture: a Flow stand-in offering {@code workflow.execute} whose claim, like the real Flow claim, needs the signed
     * contract and lists Foundry's {@code agent.run} as optional, so it composes with or without Foundry.
     */
    static Path writeFlowJar(Path dir, String fileName) throws IOException {
        return writeCatalogJarWithOptionalDependencies(dir, fileName, "flow", "workflow.execute",
                java.util.List.of(FLOW_CONTRACT_DEPENDENCY), java.util.List.of(FLOW_AGENT_DEPENDENCY));
    }

    /**
     * Fixture: a catalog jar whose claim has {@code dependencies.needs} and, when given, {@code dependencies.optional}.
     *
     * @param needs    required dependency URIs; at least one, because the manifest template writes the optional list
     *                 as a sibling block after the needs block.
     * @param optional optional dependency URIs; when empty the claim has no optional block.
     */
    static Path writeCatalogJarWithOptionalDependencies(
            Path dir,
            String fileName,
            String artifact,
            String capability,
            java.util.List<String> needs,
            java.util.List<String> optional) throws IOException {
        if (needs.isEmpty()) {
            throw new IllegalArgumentException("this fixture needs at least one required dependency");
        }
        String needsYaml = needs.stream()
                .map(dep -> "                      - \"" + dep + "\"")
                .collect(java.util.stream.Collectors.joining("\n"));
        // The manifest template is a text block, so its own indentation is stripped while inserted text is not: the
        // optional key must sit at the stripped depth of "needs:" (4 spaces), and its items deeper.
        String optionalYaml = optional.stream()
                .map(dep -> "      - \"" + dep + "\"")
                .collect(java.util.stream.Collectors.joining("\n"));
        return writeCatalogJar(dir, fileName, artifact, capability, "Unfurl", "ACTIVE",
                optional.isEmpty() ? needsYaml : needsYaml + "\n    optional:\n" + optionalYaml);
    }

    /** Claim URI of the identity provider component, as published by unfurl-adapter-keycloak. */
    static final String IDENTITY_PROVIDER_URI = "urn:unfurl:adapter:keycloak";

    /**
     * Fixture: a catalog jar mirroring the unfurl-adapter-keycloak component (HLD-needs-driven-identity §4): an
     * INFRASTRUCTURE claim offering {@code identity-context}, deployable as a container (default) or as a managed external
     * adapter, whose only need is the externally owned relational database. Kept self-contained so Fabric's tests do not
     * depend on that repository.
     */
    static Path writeIdentityProviderJar(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("identity-provider.jar");
        String manifest = """
                claim:
                  identity:
                    uri: %s
                    name: unfurl-adapter-keycloak
                    kind: INFRASTRUCTURE
                    version: 0.1.0
                    publisher: Unfurl
                  domain:
                    summary: Open-source Keycloak as the composition's identity provider.
                    concerns:
                      - concern: identity-context
                        description: Authenticated subjects and their tenant, roles and permissions claims.
                    boundary_principles:
                      - Keycloak is integrated, never rebuilt.
                  refusals:
                    - concern: authorization-decisions
                      rationale: The consuming runtime's policy decides what a caller may do.
                      owned_by: consuming runtime policy
                  dependencies:
                    needs:
                      - "relational-database@v1?transport=jdbc&owner=customer-database"
                  offers:
                    - capability: identity-context
                      description: Authenticated subject plus authorization claims.
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
                    coordinates: com.unfurl.adapters.keycloak:unfurl-adapter-keycloak:0.1.0
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: CONTAINER
                    supported_modes: [CONTAINER, MANAGED_ADAPTER]
                  component_shape_profile:
                    default_shape: CONTAINERIZED_SERVICE
                    supported_shapes: [CONTAINERIZED_SERVICE, MANAGED_EXTERNAL_ADAPTER]
                    shape_runtime: {}
                """.formatted(IDENTITY_PROVIDER_URI);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Fixture: compiles and signs a Foundry composition ({@code agent.run}), optionally with the identity provider component,
     * so runtime-binding generation sees an identity-context child contract exactly as a real release would.
     */
    static SignedPaths compileAndSignFoundry(Path dir, boolean withIdentity) throws IOException {
        return compileAndSignFoundry(dir, withIdentity, null);
    }

    /**
     * Fixture variant: compiles with an optional deployment-policy file, so a test can make Fabric select a non-default shape
     * (for example the Keycloak adapter's external shape).
     */
    static SignedPaths compileAndSignFoundry(Path dir, boolean withIdentity, Path deploymentPolicy) throws IOException {
        Path catalog = dir.resolve("catalog");
        writeFoundryJar(catalog, "agent.jar");
        StringBuilder needsText = new StringBuilder("""
                requiredCapabilities:
                  - capability: agent.run
                    capabilityVersion: ^1
                """);
        if (withIdentity) {
            writeIdentityProviderJar(catalog);
            needsText.append("""
                      - capability: identity-context
                        capabilityVersion: ^1
                    """);
        }
        Path needs = dir.resolve("needs.yaml");
        Files.writeString(needs, needsText, StandardCharsets.UTF_8);
        Path compiled = dir.resolve("compiled.yaml");
        java.util.List<String> argv = new java.util.ArrayList<>(java.util.List.of("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", compiled.toString()));
        if (deploymentPolicy != null) {
            argv.addAll(java.util.List.of("--deployment-policy", deploymentPolicy.toString()));
        }
        CliRun compile = run(argv.toArray(String[]::new));
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
