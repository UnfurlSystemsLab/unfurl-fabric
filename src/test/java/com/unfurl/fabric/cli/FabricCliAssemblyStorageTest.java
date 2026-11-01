package com.unfurl.fabric.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.fabric.assembly.AssemblyStorageService;
import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests: exercise real compile/sign/source/release/binding commands using isolated catalog artifacts and offline keys. */
class FabricCliAssemblyStorageTest {
    private static final String URI = "urn:unfurl:assembly:test-composite";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Verify all three families survive publication/reload; randomized signing retries retain exact original release bytes. */
    @ParameterizedTest @ValueSource(strings = {"flow-only", "foundry-only", "flow-foundry"})
    void publishesVerifiesAndRetriesWithoutReplacingBytes(String family, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir, family);
        assertOk(publish(fixture));
        Path manifest = release(fixture).resolve("manifest.json");
        byte[] before = Files.readAllBytes(manifest);
        assertOk(publish(fixture));
        assertThat(Files.readAllBytes(manifest)).isEqualTo(before);
        assertOk(verify(fixture));
        Files.writeString(fixture.source().resolve("definition.yaml"), "\n# changed source bytes\n", StandardOpenOption.APPEND);
        assertThat(publish(fixture).exitCode()).isNotZero();
        assertThat(Files.readAllBytes(manifest)).isEqualTo(before);
        assertOk(verify(fixture));
    }

    /** Verify retained artifacts and source bytes cannot be modified without failing offline release authentication. */
    @ParameterizedTest @ValueSource(strings = {"source/definition.yaml", "compiled/signed.yaml", "compiled/substrate.yaml", "manifest.json"})
    void detectsTampering(String path, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir, "flow-foundry");
        assertOk(publish(fixture));
        Files.writeString(release(fixture).resolve(path), "tampered");
        assertThat(verify(fixture).exitCode()).isNotZero();
    }

    /** Even a valid compiler signature cannot authorize a missing referenced contract in a published release. */
    @Test void rejectsIncompleteSignedClosure(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir, "flow-foundry");
        var codec = new com.unfurl.fabric.signing.SignedFabricContractCodec();
        var original = codec.parse(Files.readAllBytes(fixture.signed())).contract();
        var incomplete = new com.unfurl.fabric.compiler.CompiledContract(original.contract(), List.of(), original.selections(),
                original.audit(), original.substrateProfileHash(), original.bindingPlan(), null);
        var privateKey = com.unfurl.fabric.signing.SigningKeyLoader.loadPrivateKey(fixture.privateKey());
        var publicKey = com.unfurl.fabric.signing.SigningKeyLoader.loadPublicKey(fixture.publicKey());
        var signer = new com.unfurl.fabric.signing.FabricContractSigner(privateKey.key(), privateKey.signatureAlgorithm(), publicKey.fingerprint());
        Files.write(fixture.signed(), codec.write(signer.signCompiledContract(incomplete)));
        assertThat(publish(fixture).exitCode()).isNotZero();
        assertThat(Files.exists(release(fixture))).isFalse();
    }

    /** Artifact bytes are pinned independently of filenames, and family changes cannot relabel the selected runtime. */
    @Test void rejectsArtifactDriftAndWrongFamily(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir, "flow-only");
        Path wrongSource = dir.resolve("assemblies/foundry-only/test");
        Files.createDirectories(wrongSource.getParent()); Files.move(fixture.source(), wrongSource);
        Fixture wrong = new Fixture(dir, wrongSource, fixture.catalog(), fixture.needs(), fixture.signed(), fixture.profile(),
                fixture.privateKey(), fixture.publicKey(), fixture.trust(), fixture.repository());
        assertThat(publish(wrong).exitCode()).isNotZero();
        Files.move(wrongSource, fixture.source());
        Files.write(fixture.catalog().resolve("workflow.execute.jar"), new byte[]{1}, StandardOpenOption.APPEND);
        assertThat(publish(fixture).exitCode()).isNotZero();
        assertThat(Files.exists(release(fixture))).isFalse();
    }

    /** Verify deployment bindings are immutable, environment-specific, reloaded independently, and reject foreign/secret-bearing inputs. */
    @Test void storesSeparateEnvironmentBindingsAndRejectsInvalidInputs(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir, "flow-foundry");
        assertOk(publish(fixture));
        for (String env : List.of("dev", "prod")) {
            Path bindings = dir.resolve(env + ".yaml");
            assertOk(CliTestFixtures.run("runtime-bindings", "--signed-contract", fixture.signed().toString(), "--out", bindings.toString(),
                    "--tenant", "acme", "--environment", env));
            assertOk(bind(fixture, bindings, env));
            assertOk(bind(fixture, bindings, env));
            assertOk(CliTestFixtures.run("assembly-verify-binding", "--repository", fixture.repository().toString(),
                    "--deployment-repository", dir.resolve("deployments").toString(), "--uri", URI, "--version", "1.0.0",
                    "--tenant", "acme", "--environment", env, "--revision", "r1", "--trust-keys", fixture.trust().toString()));
        }
        assertThat(bind(fixture, dir.resolve("dev.yaml"), "prod").exitCode()).isNotZero();
        ObjectNode wrapper = (ObjectNode) YAML.readTree(Files.readAllBytes(dir.resolve("dev.yaml")));
        ObjectNode binding = (ObjectNode) wrapper.path("runtime_binding_set").path("runtime_bindings").get(0);
        ((ObjectNode) binding.path("configuration").path("values")).put("password", "inline-credential");
        Path unsafe = dir.resolve("unsafe.yaml"); YAML.writeValue(unsafe.toFile(), wrapper);
        assertThat(bind(fixture, unsafe, "dev").exitCode()).isNotZero();
        binding.remove("configuration"); binding.put("contract_id", "urn:foreign:contract");
        YAML.writeValue(unsafe.toFile(), wrapper);
        assertThat(bind(fixture, unsafe, "dev").exitCode()).isNotZero();
        assertOk(verify(fixture));
    }

    /** Ensure wrong keys, unknown profile constraints, substrate drift and unsafe release locators cannot create a release. */
    @Test void rejectsInvalidPublicationInputs(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir, "flow-only");
        Files.writeString(fixture.source().resolve("profiles/default.yaml"), "forbiddenCapabilities: [tool.call]\n", StandardOpenOption.APPEND);
        assertThat(publish(fixture).exitCode()).isNotZero();
        Files.copy(fixture.needs(), fixture.source().resolve("profiles/default.yaml"), StandardCopyOption.REPLACE_EXISTING);
        byte[] profile = Files.readAllBytes(fixture.profile());
        Files.writeString(fixture.profile(), "invalid: profile");
        assertThat(publish(fixture).exitCode()).isNotZero();
        Files.write(fixture.profile(), profile);
        var wrong = SigningTestFixtures.generateEcKeyPair();
        SigningTestFixtures.writePublicKeyPem(fixture.trust(), "pub.pem", wrong.getPublic());
        assertThat(publish(fixture).exitCode()).isNotZero();
        assertThat(Files.exists(release(fixture))).isFalse();
        assertThat(CliTestFixtures.run("assembly-verify", "--repository", fixture.repository().toString(), "--uri", URI,
                "--version", "../../outside", "--trust-keys", fixture.trust().toString()).exitCode()).isNotZero();
    }

    /** Setup real compiler inputs, then initialize a publisher source using an existing catalog manifest and needs document. */
    private Fixture fixture(Path dir, String family) throws Exception {
        Path catalog = dir.resolve("catalog");
        List<String> capabilities = family.equals("flow-only") ? List.of("workflow.execute")
                : family.equals("foundry-only") ? List.of("agent.run") : List.of("workflow.execute", "agent.run");
        for (String capability : capabilities) CliTestFixtures.writeCatalogJar(catalog, capability + ".jar", capability, capability);
        Path needs = dir.resolve("needs.yaml");
        StringBuilder text = new StringBuilder("requiredCapabilities:\n");
        for (String capability : capabilities) text.append("  - capability: ").append(capability).append("\n    capabilityVersion: ^1\n");
        Files.writeString(needs, text);
        Path compiled = dir.resolve("compiled.yaml");
        assertOk(CliTestFixtures.run("compile", "--catalog", catalog.toString(), "--needs", needs.toString(), "--out", compiled.toString()));
        var pair = SigningTestFixtures.generateEcKeyPair();
        Path privateKey = SigningTestFixtures.writePrivateKeyPem(dir, "private.pem", pair.getPrivate());
        Path publicKey = SigningTestFixtures.writePublicKeyPem(dir, "public.pem", pair.getPublic());
        Path trust = dir.resolve("trust"); Files.createDirectories(trust);
        SigningTestFixtures.writePublicKeyPem(trust, "pub.pem", pair.getPublic());
        Path signed = dir.resolve("signed.yaml");
        assertOk(CliTestFixtures.run("sign", "--contract", compiled.toString(), "--key", privateKey.toString(), "--public-key", publicKey.toString(), "--out", signed.toString()));
        ObjectNode definition;
        try (JarFile jar = new JarFile(catalog.resolve(capabilities.get(0) + ".jar").toFile())) {
            definition = (ObjectNode) YAML.readTree(jar.getInputStream(jar.getJarEntry("META-INF/unfurl-catalog.yaml")));
        }
        ObjectNode claim = (ObjectNode) definition.get("claim");
        ((ObjectNode) claim.get("identity")).put("uri", URI).put("name", "Test composite");
        claim.putObject("metadata").put("dcp_version", "0.2.0").put("claim_version", "1.0.0");
        claim.putArray("refusals").addObject().put("concern", "workflow-execution-hosting").put("rationale", "The owning runtime hosts all execution");
        Path definitionFile = dir.resolve("definition.yaml"); YAML.writeValue(definitionFile.toFile(), definition);
        assertOk(CliTestFixtures.run("assembly-init", "--workspace", dir.toString(), "--family", family, "--name", "test",
                "--definition", definitionFile.toString(), "--needs", needs.toString(), "--profile", "default"));
        return new Fixture(dir, dir.resolve("assemblies").resolve(family).resolve("test"), catalog, needs, signed,
                CliSupport.defaultProfilePath(compiled), privateKey, publicKey, trust, dir.resolve("releases"));
    }

    /** Command helper: publish all required real inputs to the isolated local repository. */
    private CliTestFixtures.CliRun publish(Fixture f) {
        return CliTestFixtures.run("assembly-publish", "--source", f.source().toString(), "--profile", "default", "--version", "1.0.0",
                "--signed-contract", f.signed().toString(), "--substrate-profile", f.profile().toString(), "--catalog", f.catalog().toString(),
                "--repository", f.repository().toString(), "--key", f.privateKey().toString(), "--public-key", f.publicKey().toString(), "--trust-keys", f.trust().toString());
    }

    /** Command helper: offline release verification uses only the saved bundle and pinned public keys. */
    private CliTestFixtures.CliRun verify(Fixture f) {
        return CliTestFixtures.run("assembly-verify", "--repository", f.repository().toString(), "--uri", URI, "--version", "1.0.0", "--trust-keys", f.trust().toString());
    }

    /** Command helper: deployment-owner admission of caller-supplied bindings for one scoped revision. */
    private CliTestFixtures.CliRun bind(Fixture f, Path bindings, String env) {
        return CliTestFixtures.run("assembly-bind", "--repository", f.repository().toString(), "--deployment-repository", f.dir().resolve("deployments").toString(),
                "--uri", URI, "--version", "1.0.0", "--tenant", "acme", "--environment", env, "--revision", "r1", "--bindings", bindings.toString(),
                "--key", f.privateKey().toString(), "--public-key", f.publicKey().toString(), "--trust-keys", f.trust().toString());
    }

    /** Locator helper: use production key construction so tests inspect the actual stored bundle. */
    private Path release(Fixture f) throws Exception { return f.repository().resolve(AssemblyStorageService.releaseKey(URI, "1.0.0")); }

    /** Assertion helper: surface command diagnostics at the failing lifecycle boundary. */
    private void assertOk(CliTestFixtures.CliRun run) { assertThat(run.exitCode()).withFailMessage(run.stderr()).isZero(); }

    /** Value Object: paths belonging to one independently compiled and signed test assembly. */
    private record Fixture(Path dir, Path source, Path catalog, Path needs, Path signed, Path profile,
                           Path privateKey, Path publicKey, Path trust, Path repository) { }
}
