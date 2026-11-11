package com.unfurl.fabric.cli;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.Party;
import com.unfurl.deployment.domain.ComponentHost;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.catalog.SkippedEntry;
import com.unfurl.fabric.compile.ContractCompiler;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests Fabric's hosted-component pipeline (HLD-in-process-component-hosting §3–§5) on real jars: scan indexes
 * companions and refuses unresolvable ones, deployment resolution places the hosted component on the host, and compile
 * names the host as consumer, pins the companions, and refuses a pinned jar that shadows the host's shared API.
 */
class HostedComponentPipelineTest {
    private static final String SHAPE = "com.unfurl.foundry.substrate.offers.ToolCapabilityRegistrar";
    private static final String HOST = "com.example:agent-host:1.0.0";
    private static final String HOSTED = "com.example:broker-adapter:1.0.0";
    private static final String COMPANION = "com.example:broker-http:1.0.0";

    @TempDir Path catalog;

    /** The hosted component is placed on the only candidate host, names it as consumer and pins its companion. */
    @Test
    void placesTheHostedComponentAndPinsItsCompanions() throws Exception {
        writeHost();
        writeHosted(List.of(COMPANION));
        Path companion = companion("broker-http-1.0.0.jar", COMPANION, Map.of("com/example/http/Client.class", new byte[]{1}));
        Files.copy(companion, catalog.resolve("broker-http-copy.jar"));

        CatalogScanReport scan = new CatalogScanner().scan(catalog);
        assertThat(scan.catalog().entries()).hasSize(2);
        assertThat(scan.catalog().companions()).containsOnlyKeys(COMPANION);
        assertThat(scan.catalog().companions().get(COMPANION).copies()).hasSize(2);
        assertThat(scan.skippedEntries()).isEmpty();

        CompiledContract compiled = CliSupport.compileCandidate(candidate(scan.catalog().entries()), need(), "EXACT").contract();

        assertThat(compiled.bindingPlan().entries()).filteredOn(entry -> entry.componentId().equals(HOSTED))
                .extracting(entry -> entry.host()).containsOnly(Optional.of(new ComponentHost.InternalHost(HOST)));
        CompositionContract hosted = child(compiled, "broker.read");
        assertThat(hosted.parties().consumer()).isEqualTo(new Party(URI.create("urn:example:agent-host"), "1.0.0"));
        assertThat(hosted.metadata().extensions().get(ContractCompiler.EXT_COMPANION_ARTIFACTS)).isEqualTo(List.of(Map.of(
                "coordinates", COMPANION, "sha256", scan.catalog().companions().get(COMPANION).sha256())));
        CompositionContract hostEdge = child(compiled, "agent.run");
        assertThat(hostEdge.parties().consumer()).isNotEqualTo(hosted.parties().consumer());
        assertThat(hostEdge.metadata().extensions()).doesNotContainKey(ContractCompiler.EXT_COMPANION_ARTIFACTS);

        // verify --catalog: a pinned companion rebuilt with other bytes, or removed, is drift (§10).
        var drift = new com.unfurl.fabric.verify.CatalogDriftChecker();
        assertThat(drift.check(compiled, scan.catalog()).clean()).isTrue();
        Files.delete(catalog.resolve("broker-http-copy.jar"));
        companion("broker-http-1.0.0.jar", COMPANION, Map.of("com/example/http/Client.class", new byte[]{2}));
        assertThat(drift.check(compiled, new CatalogScanner().scan(catalog).catalog()).hashDrifts())
                .extracting(com.unfurl.fabric.verify.CatalogDriftReport.DriftEntry::coordinates).containsExactly(COMPANION);
        Files.delete(catalog.resolve("broker-http-1.0.0.jar"));
        assertThat(drift.check(compiled, new CatalogScanner().scan(catalog).catalog()).missingFromCatalog())
                .extracting(com.unfurl.fabric.verify.CatalogDriftReport.MissingEntry::coordinates).contains(COMPANION);
    }

    /** Different bytes under one companion's coordinates, or a missing companion, make the hosted entry unselectable. */
    @Test
    void refusesConflictingAndMissingCompanions(@TempDir Path work) throws Exception {
        writeHost();
        writeHosted(List.of(COMPANION, "com.example:absent:1.0.0"));
        companion("broker-http-a.jar", COMPANION, Map.of("a.txt", new byte[]{1}));
        companion("broker-http-b.jar", COMPANION, Map.of("a.txt", new byte[]{2}));

        CatalogScanReport scan = new CatalogScanner().scan(catalog);

        assertThat(scan.catalog().entries()).extracting(entry -> entry.artifact().coordinates()).containsExactly(HOST);
        assertThat(scan.catalog().companions()).doesNotContainKey(COMPANION);
        assertThat(scan.skippedEntries()).singleElement().satisfies(skip -> {
            assertThat(skip.reason()).isEqualTo(SkippedEntry.SkipReason.COMPANION_UNRESOLVED);
            assertThat(skip.detail()).startsWith("CATALOG_COMPANION_CONFLICT: " + COMPANION + " has different bytes in ")
                    .contains("broker-http-a.jar", "broker-http-b.jar");
        });

        Files.delete(catalog.resolve("broker-http-b.jar"));
        assertThat(new CatalogScanner().scan(catalog).skippedEntries()).singleElement()
                .satisfies(skip -> assertThat(skip.detail()).isEqualTo("CATALOG_COMPANION_MISSING: the catalog has no companion com.example:absent:1.0.0"));

        // plan says why the hosted component is not a candidate (HLD-in-process-component-hosting §10).
        Path needs = Files.writeString(work.resolve("needs.yaml"),
                "requiredCapabilities:\n  - capability: agent.run\n    capabilityVersion: ^1\n");
        var plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());
        assertThat(plan.stdout()).contains("companions missing: broker-adapter.jar CATALOG_COMPANION_MISSING: the catalog has no "
                + "companion com.example:absent:1.0.0");
    }

    /** A manifest-less jar needs exactly one pom.properties to be a companion candidate. */
    @Test
    void reportsJarsThatAreNotCompanionCandidates() throws Exception {
        writeJar(catalog.resolve("plain.jar"), Map.of("x.txt", new byte[]{1}));
        Map<String, byte[]> shaded = new LinkedHashMap<>();
        shaded.put("META-INF/maven/a/one/pom.properties", pom("a", "one", "1").getBytes(StandardCharsets.UTF_8));
        shaded.put("META-INF/maven/b/two/pom.properties", pom("b", "two", "1").getBytes(StandardCharsets.UTF_8));
        writeJar(catalog.resolve("shaded.jar"), shaded);

        CatalogScanReport scan = new CatalogScanner().scan(catalog);

        assertThat(scan.catalog().companions()).isEmpty();
        assertThat(scan.skippedEntries()).extracting(SkippedEntry::detail)
                .anySatisfy(detail -> assertThat(detail).contains("it has 0 pom.properties entries"))
                .anySatisfy(detail -> assertThat(detail).contains("it has 2 pom.properties entries"));
    }

    /** A pinned jar with classes in a package the host shares is refused at compile, as the host would at startup. */
    @Test
    void refusesACompanionThatShadowsTheHostsSharedApi() throws Exception {
        writeHost();
        writeHosted(List.of(COMPANION));
        companion("broker-http-1.0.0.jar", COMPANION, Map.of("com/unfurl/dcp/host/Evil.class", new byte[]{1}));

        CatalogScanReport scan = new CatalogScanner().scan(catalog);

        assertThatThrownBy(() -> CliSupport.compileCandidate(candidate(scan.catalog().entries()), need(), "EXACT"))
                .hasMessageContaining("COMPANION_SHADOWS_SHARED_API: " + COMPANION)
                .hasMessageContaining("com/unfurl/dcp/host/Evil.class");
    }

    /** With two hosts and no policy, placement is ambiguous and nothing is compiled. */
    @Test
    void refusesAnAmbiguousHost() throws Exception {
        writeHost();
        writeJar(catalog.resolve("other-host.jar"), Map.of(CatalogScanner_MANIFEST, hostManifest("urn:example:other-host",
                "com.example:other-host:1.0.0", "other.run").getBytes(StandardCharsets.UTF_8)));
        writeHosted(List.of());

        CatalogScanReport scan = new CatalogScanner().scan(catalog);

        assertThatThrownBy(() -> CliSupport.compileCandidate(candidate(scan.catalog().entries()), need(), "EXACT"))
                .hasMessageContaining("HOST_PLACEMENT_AMBIGUOUS");
    }

    private static final String CatalogScanner_MANIFEST = "META-INF/unfurl-catalog.yaml";
    private static final String PACKAGED_CONFIG = """
            config_version: 1
            schema:
              maxRows:    { type: integer, min: 1, max: 1000, source: packaged, override: tighten, stricter: lower }
              region:     { type: string, source: packaged }
              accountRef: { type: string, source: deployment }
              sessionRef: { type: secret-ref, source: deployment }
            defaults:
              maxRows: 100
              region: ap-south
            """;

    /**
     * The CLI end to end: compile places and pins, {@code sign --dcp-runtime-bundle} writes verifiable frozen child
     * contracts, and {@code runtime-bindings --component-config} writes an IN_PROCESS binding consumed by the host that
     * DCP's strict codec reads back, with the deployment values typed by the packaged schema.
     */
    @Test
    void writesSignedBundleAndInProcessBindingsThroughTheCli(@TempDir Path work) throws Exception {
        writeHost();
        writeHosted(List.of(), PACKAGED_CONFIG);
        Path needs = Files.writeString(work.resolve("needs.yaml"), """
                requiredCapabilities:
                  - capability: broker.read
                    capabilityVersion: ^1
                  - capability: agent.run
                    capabilityVersion: ^1
                """);
        Path compiled = work.resolve("compiled.yaml");
        assertExit(0, CliTestFixtures.run("compile", "--catalog", catalog.toString(), "--needs", needs.toString(), "--out", compiled.toString()));

        var keys = com.unfurl.fabric.signing.SigningTestFixtures.generateEcKeyPair();
        Path privateKey = com.unfurl.fabric.signing.SigningTestFixtures.writePrivateKeyPem(work, "key.pem", keys.getPrivate());
        Path publicKey = com.unfurl.fabric.signing.SigningTestFixtures.writePublicKeyPem(work, "pub.pem", keys.getPublic());
        Path signed = work.resolve("signed.yaml");
        Path bundle = work.resolve("bundle.zip");
        assertExit(0, CliTestFixtures.run("sign", "--contract", compiled.toString(), "--key", privateKey.toString(),
                "--public-key", publicKey.toString(), "--out", signed.toString(),
                "--dcp-runtime-bundle", bundle.toString(), "--catalog", catalog.toString()));

        // The bundle's frozen child contracts verify against the signing key, exactly as a host loads them.
        Path frozen = Files.createDirectories(work.resolve("frozen"));
        try (var zip = new java.util.zip.ZipFile(bundle.toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                if (entry.getName().startsWith("contracts/frozen/")) {
                    Files.write(frozen.resolve(Path.of(entry.getName()).getFileName().toString()), zip.getInputStream(entry).readAllBytes());
                }
            }
            assertThat(zip.getEntry("claims/urn-example-broker-adapter.claim.json")).isNotNull();
        }
        Path trust = copyKey(publicKey, Files.createDirectories(work.resolve("trust")));
        var verified = com.unfurl.dcp.host.VerifiedContracts.loadDirectory(frozen,
                new com.unfurl.dcp.trust.VerificationKeyPemLoader().loadDirectory(trust));
        assertThat(verified.all()).hasSize(2);
        var hostedContract = verified.all().stream().filter(c -> c.contract().binding().providerCapability().equals("broker.read"))
                .findFirst().orElseThrow();
        assertThat(hostedContract.contract().parties().consumer().claimUri()).isEqualTo(URI.create("urn:example:agent-host"));

        Path bindings = work.resolve("runtime-binding.yaml");
        assertExit(0, CliTestFixtures.run("runtime-bindings", "--signed-contract", signed.toString(), "--out", bindings.toString(),
                "--catalog", catalog.toString(),
                "--component-config", "urn:example:broker-adapter#accountRef=primary-equity",
                "--component-config", "urn:example:broker-adapter#sessionRef=secret://demo/broker/session",
                "--component-config", "urn:example:broker-adapter#maxRows=50"));

        var set = com.unfurl.dcp.runtimebinding.RuntimeBindingCodec.read(Files.readAllBytes(bindings));
        var binding = set.byContractId().get(hostedContract.contract().contractId());
        assertThat(binding.providerInstance().deploymentKind()).isEqualTo(com.unfurl.dcp.runtimebinding.DeploymentKind.IN_PROCESS);
        assertThat(binding.providerInstance().baseUrl()).isNull();
        assertThat(binding.consumerInstance().componentUri()).isEqualTo(URI.create("urn:example:agent-host"));
        assertThat(binding.configuration().values()).containsEntry("accountRef", "primary-equity")
                .containsEntry("sessionRef", "secret://demo/broker/session").containsEntry("maxRows", 50)
                .doesNotContainKey("region");

        // plan and explain-deployment show the placement (HLD-in-process-component-hosting §10).
        var plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());
        assertThat(plan.stdout()).contains("hosted: " + HOSTED + " by " + HOST,
                "deployment keys: urn:example:broker-adapter requires accountRef (string), sessionRef (secret-ref); "
                        + "may change maxRows (tighten)");
        var explained = CliTestFixtures.run("explain-deployment", "--contract", signed.toString());
        assertThat(explained.stdout()).contains("  host=" + HOST, "  companionArtifacts=[]", "  host=<none>");
        var verify = CliTestFixtures.run("verify", "--contract", signed.toString(), "--trust-keys", trust.toString(),
                "--catalog", catalog.toString());
        assertThat(verify.stdout()).contains("catalogClean=true");

        // emit hands the catalog to the emitter for the admitted-component overlay (§8), and refuses to run without it.
        Path profile = CliSupport.defaultProfilePath(compiled);
        Path out = work.resolve("emit");
        var emitted = CliTestFixtures.run("deploy", "--targets", "local,remote", "--contract", signed.toString(), "--profile",
                profile.toString(), "--trust-keys", trust.toString(), "--out", out.toString(), "--runtime-binding",
                bindings.toString(), "--dcp-runtime-bundle", bundle.toString(), "--catalog", catalog.toString());
        assertExit(0, emitted);
        assertThat(Files.readString(out.resolve("local-compose/local-compose-artifact.txt")))
                .contains("componentArtifactsPath=" + catalog.toAbsolutePath());
        assertThat(emitted.stdout()).contains("operatorDelivery=" + out.resolve("local-compose/hosted-exports/com-example-agent-host-1-0-0")
                .toAbsolutePath().normalize() + " host=" + HOST + "@test (not mounted or started: deliver it to that host)");
        var noCatalog = CliTestFixtures.run("emit", "--target", "local", "--contract", signed.toString(), "--profile",
                profile.toString(), "--trust-keys", trust.toString(), "--out", work.resolve("emit2").toString());
        assertThat(noCatalog.exitCode()).isNotZero();
        assertThat(noCatalog.stderr()).contains("--catalog must name the catalog folder");
    }


    /** Deployment values that break the packaged schema are refused before any binding is written, all at once. */
    @Test
    void refusesComponentConfigurationThePackagedSchemaForbids(@TempDir Path work) throws Exception {
        writeHost();
        writeHosted(List.of(), PACKAGED_CONFIG);
        Path needs = Files.writeString(work.resolve("needs.yaml"),
                "requiredCapabilities:\n  - capability: broker.read\n    capabilityVersion: ^1\n  - capability: agent.run\n    capabilityVersion: ^1\n");
        Path compiled = work.resolve("compiled.yaml");
        assertExit(0, CliTestFixtures.run("compile", "--catalog", catalog.toString(), "--needs", needs.toString(), "--out", compiled.toString()));
        var keys = com.unfurl.fabric.signing.SigningTestFixtures.generateEcKeyPair();
        Path signed = work.resolve("signed.yaml");
        assertExit(0, CliTestFixtures.run("sign", "--contract", compiled.toString(),
                "--key", com.unfurl.fabric.signing.SigningTestFixtures.writePrivateKeyPem(work, "k.pem", keys.getPrivate()).toString(),
                "--public-key", com.unfurl.fabric.signing.SigningTestFixtures.writePublicKeyPem(work, "p.pem", keys.getPublic()).toString(),
                "--out", signed.toString()));
        Path bindings = work.resolve("runtime-binding.yaml");

        var refused = CliTestFixtures.run("runtime-bindings", "--signed-contract", signed.toString(), "--out", bindings.toString(),
                "--catalog", catalog.toString(),
                "--component-config", "urn:example:broker-adapter#sessionRef=hunter2-inline",
                "--component-config", "urn:example:broker-adapter#maxRows=5000",
                "--component-config", "urn:example:broker-adapter#region=eu-west",
                "--component-config", "urn:example:other#x=1");
        assertThat(refused.exitCode()).isNotZero();
        assertThat(refused.stderr()).contains("CONFIG_REQUIRED_MISSING [accountRef]", "CONFIG_INVALID [maxRows]",
                "CONFIG_KEY_FIXED [region]", "CONFIG_SECRET_INLINE [sessionRef]", "names urn:example:other, which is not a hosted component")
                .doesNotContain("hunter2-inline");
        assertThat(bindings).doesNotExist();

        var noCatalog = CliTestFixtures.run("runtime-bindings", "--signed-contract", signed.toString(), "--out", bindings.toString());
        assertThat(noCatalog.stderr()).contains("--catalog is required");
    }

    private static Path copyKey(Path publicKey, Path directory) throws IOException {
        Files.copy(publicKey, directory.resolve("pub.pem"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return directory;
    }

    private static void assertExit(int expected, CliTestFixtures.CliRun run) {
        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(expected);
    }

    private void writeHost() throws IOException {
        writeJar(catalog.resolve("agent-host.jar"), Map.of(CatalogScanner_MANIFEST,
                hostManifest("urn:example:agent-host", HOST, "agent.run").getBytes(StandardCharsets.UTF_8)));
    }

    private void writeHosted(List<String> companions) throws IOException {
        writeHosted(companions, null);
    }

    /** Writes the hosted component's jar, with a packaged {@code META-INF/unfurl-config.yaml} when one is given. */
    private void writeHosted(List<String> companions, String packagedConfig) throws IOException {
        String list = companions.isEmpty() ? "[]" : "[" + String.join(", ", companions) + "]";
        String manifest = manifest("urn:example:broker-adapter", HOSTED, "broker.read", "IN_PROCESS",
                "  hosted_activation:\n    registrar_shapes: [" + SHAPE + "]\n    companions: " + list + "\n");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(CatalogScanner_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
        if (packagedConfig != null) entries.put("META-INF/unfurl-config.yaml", packagedConfig.getBytes(StandardCharsets.UTF_8));
        writeJar(catalog.resolve("broker-adapter.jar"), entries);
    }

    private static String hostManifest(String uri, String coordinates, String capability) {
        return manifest(uri, coordinates, capability, "REMOTE_HTTP",
                "  component_host:\n    registrar_shape: " + SHAPE + "\n    shared_api_prefixes: [com.unfurl.foundry.substrate.]\n");
    }

    private Path companion(String fileName, String coordinates, Map<String, byte[]> extra) throws IOException {
        String[] parts = coordinates.split(":");
        Map<String, byte[]> entries = new LinkedHashMap<>(extra);
        entries.put("META-INF/maven/" + parts[0] + "/" + parts[1] + "/pom.properties",
                pom(parts[0], parts[1], parts[2]).getBytes(StandardCharsets.UTF_8));
        return writeJar(catalog.resolve(fileName), entries);
    }

    private static String pom(String group, String artifact, String version) {
        return "groupId=" + group + "\nartifactId=" + artifact + "\nversion=" + version + "\n";
    }

    private static Path writeJar(Path file, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    private static CompositionContract child(CompiledContract compiled, String capability) {
        return compiled.childContracts().stream().filter(c -> c.binding().providerCapability().equals(capability)).findFirst().orElseThrow();
    }

    private static Need need() {
        return Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("broker.read", "^1"));
    }

    private static CompositionCandidate candidate(List<CatalogEntry> entries) {
        return new CompositionCandidate(CompositionCandidate.computeId(entries), entries, Set.of("broker.read"), Set.of(),
                List.of(), List.of(), CandidateScore.of(0, 5, 10, 5, 30, 30, 0, 0));
    }

    /** A schema-valid manifest with one offer and the given extra catalog lines. */
    private static String manifest(String uri, String coordinates, String capability, String mode, String catalogExtra) {
        return """
                claim:
                  identity:
                    uri: %s
                    name: test component
                    kind: COMPONENT
                    version: 1.0.0
                    publisher: Unfurl
                  domain:
                    summary: test
                    concerns:
                      - concern: primary
                        description: primary concern
                    boundary_principles:
                      - "deterministic boundary"
                  refusals:
                    - concern: identity-management
                      rationale: not our concern
                      owned_by: customer-idp
                  dependencies:
                    needs: []
                  offers:
                    - capability: %s
                      description: example offer
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
                    coordinates: %s
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: %s
                    supported_modes: [%s]
                %s""".formatted(uri, capability, coordinates, mode, mode, catalogExtra);
    }
}
