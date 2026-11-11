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
    }

    /** Different bytes under one companion's coordinates, or a missing companion, make the hosted entry unselectable. */
    @Test
    void refusesConflictingAndMissingCompanions() throws Exception {
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

    private void writeHost() throws IOException {
        writeJar(catalog.resolve("agent-host.jar"), Map.of(CatalogScanner_MANIFEST,
                hostManifest("urn:example:agent-host", HOST, "agent.run").getBytes(StandardCharsets.UTF_8)));
    }

    private void writeHosted(List<String> companions) throws IOException {
        String list = companions.isEmpty() ? "[]" : "[" + String.join(", ", companions) + "]";
        String manifest = manifest("urn:example:broker-adapter", HOSTED, "broker.read", "IN_PROCESS",
                "  hosted_activation:\n    registrar_shapes: [" + SHAPE + "]\n    companions: " + list + "\n");
        writeJar(catalog.resolve("broker-adapter.jar"), Map.of(CatalogScanner_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8)));
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
