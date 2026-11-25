package com.unfurl.fabric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.unfurl.dcp.claim.ArtifactRef;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimHash;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.catalog.SelectedCatalogEntries;
import com.unfurl.fabric.catalog.SkippedEntry;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.runtime.ServiceBaseUrls;
import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.fabric.signing.SigningTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specification of HLD-api-documentation-artifacts (Phase 3 of the API catalog plan), end to end through the CLI over
 * real jars that ship their schemas: the scan verifies shipped documentation (§2), the DCP runtime bundle carries it by
 * hash (§3), runtime bindings read the catalog and check its pins (§4.0), endpoints are generic and never guessed (§4.1),
 * an API catalog in the composition receives its settings behind DCP's gap gate (§4.2, §4.3), {@code documentation-check}
 * reports gaps (§6), and Keycloak's OAuth 2.0 offer is never a gap in either of its shapes (§5).
 */
class FabricCliDocumentationArtifactsTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CATALOG_URI = "urn:unfurl:api-catalog";
    private static final String LOOKUP_URI = "urn:unfurl:test:lookup";
    private static final String LEDGER_URI = "urn:unfurl:test:ledger";
    private static final String TOKENS_URI = "urn:unfurl:test:tokens";
    /** One response schema both documented fixtures ship, so the bundle must write it once. */
    private static final String SHARED_RESPONSE = "{\"type\":\"object\",\"properties\":{\"result\":{\"type\":\"string\"}}}";

    // ------------------------------------------------------------------------------------------------------ §2 scan

    /**
     * The scan verifies shipped documentation as data: valid schemas pass; a missing file, a changed file, a schema with
     * {@code $id}, and an external_protocol offer that carries documentation are each skipped with
     * DOCUMENTATION_ARTIFACT_INVALID and DCP's code; an interface-detail schema hint is a warning, and stays selectable.
     */
    @Test
    void scanVerifiesShippedDocumentation(@TempDir Path dir) throws Exception {
        Path catalog = Files.createDirectories(dir.resolve("catalog"));
        writeJar(catalog.resolve("valid.jar"), documentedJar("valid", "valid.run", "IN_PROCESS", null, SchemaFiles.valid("valid.run")));
        SchemaFiles missing = SchemaFiles.valid("missing.run");
        missing.files.remove(missing.requestPath);
        writeJar(catalog.resolve("missing.jar"), documentedJar("missing", "missing.run", "IN_PROCESS", null, missing));
        SchemaFiles changed = SchemaFiles.valid("changed.run");
        changed.files.put(changed.requestPath, "{\"type\": \"object\"}".getBytes(StandardCharsets.UTF_8));
        writeJar(catalog.resolve("changed.jar"), documentedJar("changed", "changed.run", "IN_PROCESS", null, changed));
        writeJar(catalog.resolve("scoped.jar"), documentedJar("scoped", "scoped.run", "IN_PROCESS", null,
                SchemaFiles.of("scoped.run", "{\"type\":\"object\",\"$id\":\"urn:x\"}")));
        SchemaFiles protocol = SchemaFiles.valid("protocol.token");
        Map<String, Object> protocolJar = new LinkedHashMap<>(protocol.files);
        protocolJar.put(MANIFEST, manifest("protocol", "protocol.token", "IN_PROCESS", null,
                interfaceYaml("EXTERNAL_PROTOCOL", "protocol: oauth2") + protocol.documentationYaml(), ""));
        writeJar(catalog.resolve("protocol.jar"), protocolJar);
        writeJar(catalog.resolve("hint.jar"), Map.of(MANIFEST, manifest("hint", "hint.run", "IN_PROCESS", null,
                interfaceYaml("IN_PROCESS", "input_schema: dcp/hint.json"), "")));

        CatalogScanReport report = new CatalogScanner().scan(catalog);

        assertThat(report.catalog().entries()).extracting(e -> e.claimDescriptor().claim().identity().uri().toString())
                .containsExactlyInAnyOrder("urn:unfurl:test:valid", "urn:unfurl:test:hint");
        assertThat(skip(report, "missing.jar")).contains("ARTIFACT_UNAVAILABLE", "dcp/schemas/missing.run.request.json");
        assertThat(skip(report, "changed.jar")).contains("ARTIFACT_HASH_MISMATCH");
        assertThat(skip(report, "scoped.jar")).contains("SCHEMA_DOCUMENT_INVALID");
        assertThat(skip(report, "protocol.jar")).contains("CLAIM_EXTERNAL_PROTOCOL_INVALID");
        assertThat(report.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.jarPath().getFileName().toString()).isEqualTo("hint.jar");
            assertThat(warning.code()).isEqualTo("CLAIM_DOCUMENTATION_HINT_ONLY");
        });
        CliTestFixtures.CliRun scan = CliTestFixtures.run("scan", "--catalog", catalog.toString());
        assertThat(scan.stdout()).contains("skip missing.jar DOCUMENTATION_ARTIFACT_INVALID", "warning hint.jar CLAIM_DOCUMENTATION_HINT_ONLY");
    }

    // ---------------------------------------------------------------------------------------------------- §3 bundle

    /**
     * The DCP runtime bundle carries every named file at artifacts/sha256/&lt;sha256&gt;, exactly as shipped, a shared schema
     * once, and the claims unchanged; a jar that changed after the scan fails the bundle instead of shipping other bytes.
     */
    @Test
    void bundleCarriesShippedArtifactsByHash(@TempDir Path dir) throws Exception {
        Composition composition = Composition.documented(dir, true);
        Path bundle = dir.resolve("bundle.zip");

        CliTestFixtures.CliRun sign = composition.sign(bundle);
        assertThat(sign.exitCode()).withFailMessage(sign.stderr()).isZero();

        Map<String, byte[]> entries = unzip(bundle);
        SchemaFiles lookup = SchemaFiles.valid("lookup.find");
        SchemaFiles catalogFiles = SchemaFiles.valid("documentation.catalog");
        assertThat(entries.get("artifacts/sha256/" + sha256(lookup.files.get(lookup.requestPath))))
                .isEqualTo(lookup.files.get(lookup.requestPath));
        assertThat(entries.get("artifacts/sha256/" + sha256(catalogFiles.files.get(catalogFiles.requestPath))))
                .isEqualTo(catalogFiles.files.get(catalogFiles.requestPath));
        assertThat(entries.keySet().stream().filter(name -> name.startsWith("artifacts/sha256/")))
                .as("two request schemas and one shared response schema").hasSize(3);
        CompiledContract compiled = CliTestFixtures.readCompiled(composition.compiled);
        Claim bundled = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(entries.get("claims/urn-unfurl-test-lookup.claim.json"), Claim.class);
        assertThat(compiled.selections()).extracting(SelectionRecord::claimHash).contains(ClaimHash.canonical(bundled));

        // A jar rewritten after compilation is a different artifact: its pin no longer matches, nothing is written.
        Path rebuilt = dir.resolve("rebuilt.zip");
        writeJar(composition.catalog.resolve("lookup.jar"), documentedJar("lookup", "lookup.find", "REMOTE_HTTP", "lookup:9001",
                SchemaFiles.of("lookup.find", "{\"type\":\"object\",\"title\":\"changed\"}")));
        CliTestFixtures.CliRun refused = composition.sign(rebuilt);
        assertThat(refused.exitCode()).isNotZero();
        assertThat(refused.stderr()).contains(SelectedCatalogEntries.CATALOG_ENTRY_MISSING);
        assertThat(rebuilt).doesNotExist();
    }

    // ---------------------------------------------------------------------------------------------------- §4.0 inputs

    /**
     * Binding inputs are one verified set: a selection missing from the catalog and a selection whose pinned claim hash
     * differs are each refused with their code, naming the selection.
     */
    @Test
    void selectionsResolveOnlyAgainstTheirPins(@TempDir Path dir) throws Exception {
        Composition composition = Composition.documented(dir, false);
        CompiledContract compiled = CliTestFixtures.readCompiled(composition.compiled);
        var catalog = new CatalogScanner().scan(composition.catalog).catalog();

        assertThat(SelectedCatalogEntries.resolve(compiled, catalog).entries()).hasSize(compiled.selections().size());
        assertThatThrownBy(() -> SelectedCatalogEntries.resolve(compiled, new CatalogScanner().scan(Files.createDirectories(dir.resolve("empty"))).catalog()))
                .hasMessageContaining(SelectedCatalogEntries.CATALOG_ENTRY_MISSING);
        List<SelectionRecord> repinned = new ArrayList<>(compiled.selections());
        SelectionRecord first = repinned.get(0);
        repinned.set(0, new SelectionRecord(first.artifact(), "0".repeat(64), first.bindingMode(), first.chosenInterfaceKind(),
                first.deploymentShape()));
        CompiledContract tampered = new CompiledContract(compiled.contract(), compiled.childContracts(), repinned, compiled.audit(),
                compiled.substrateProfileHash(), compiled.bindingPlan(), null);
        assertThatThrownBy(() -> SelectedCatalogEntries.resolve(tampered, catalog))
                .hasMessageContaining(SelectedCatalogEntries.CATALOG_CLAIM_MISMATCH).hasMessageContaining(first.artifact().coordinates());
    }

    // ------------------------------------------------------------------------------------------------- §4.1 endpoints

    /**
     * Endpoints are generic and never guessed: a declared service gives http://name:port/dcp/&lt;capability&gt;; an operator
     * base URL overrides it; an HTTP-served provider with neither is SERVICE_BASE_URL_MISSING; a base URL for a stranger is
     * SERVICE_BASE_URL_UNKNOWN; and an external_protocol provider gets its conventional endpoint reference, never a URL.
     */
    @Test
    void endpointsComeFromTheCatalogOrTheOperator(@TempDir Path dir) throws Exception {
        Composition composition = Composition.endpoints(dir);

        CliTestFixtures.CliRun missing = composition.bindings(dir.resolve("missing.yaml"));
        assertThat(missing.exitCode()).isNotZero();
        assertThat(missing.stderr()).contains("SERVICE_BASE_URL_MISSING: " + LEDGER_URI).doesNotContain(TOKENS_URI);

        Path out = dir.resolve("bindings.yaml");
        CliTestFixtures.CliRun ok = composition.bindings(out, "--service-base-url", LEDGER_URI + "=https://ledger.internal:8443/");
        assertThat(ok.exitCode()).withFailMessage(ok.stderr()).isZero();
        JsonNode set = YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set");
        assertThat(binding(set, "lookup.find").at("/provider_instance/base_url").asText()).isEqualTo("http://lookup:9001/dcp/lookup.find");
        assertThat(binding(set, "ledger.post").at("/provider_instance/base_url").asText())
                .isEqualTo("https://ledger.internal:8443/dcp/ledger.post");
        JsonNode tokens = binding(set, "token.issue").path("provider_instance");
        assertThat(tokens.path("base_url").isMissingNode()).isTrue();
        assertThat(tokens.at("/base_url_ref/uri").asText()).isEqualTo("config://acme/dcp/token-issue/endpoint");

        Path overridden = dir.resolve("overridden.yaml");
        assertThat(composition.bindings(overridden, "--service-base-url", LEDGER_URI + "=http://ledger:1,"
                + LOOKUP_URI + "=http://lookup.blue:9001").exitCode()).isZero();
        assertThat(binding(YAML.readTree(Files.readAllBytes(overridden)).path("runtime_binding_set"), "lookup.find")
                .at("/provider_instance/base_url").asText()).isEqualTo("http://lookup.blue:9001/dcp/lookup.find");

        CliTestFixtures.CliRun stranger = composition.bindings(dir.resolve("stranger.yaml"), "--service-base-url",
                LEDGER_URI + "=http://ledger:1", "--foundry-base-url", "http://foundry:7979");
        assertThat(stranger.exitCode()).isNotZero();
        assertThat(stranger.stderr()).contains("SERVICE_BASE_URL_UNKNOWN: a base URL is given for urn:unfurl:foundry");
    }

    /**
     * Handoff (HLD-D §6.2b, HLD-E §4.0d): the bindings and the emitted deployment read one signed plan, so every binding
     * endpoint names a service the emitter rendered, on the port that service exposes, both in Compose and in Kubernetes.
     */
    @Test
    void bindingEndpointsNameTheEmittedServices(@TempDir Path dir) throws Exception {
        Composition composition = Composition.documented(dir, true);
        Path bindings = dir.resolve("bindings.yaml");
        Path visibility = Files.writeString(dir.resolve("visibility.yaml"), "capabilities:\n  ledger.post: private\n");
        CliTestFixtures.CliRun bound = composition.bindings(bindings, "--documentation-visibility", visibility.toString());
        assertThat(bound.exitCode()).withFailMessage(bound.stderr()).isZero();
        Map<String, Integer> endpoints = new LinkedHashMap<>();
        YAML.readTree(Files.readAllBytes(bindings)).at("/runtime_binding_set/runtime_bindings").forEach(binding -> {
            String url = binding.at("/provider_instance/base_url").asText("");
            if (!url.isEmpty()) {
                java.net.URI endpoint = java.net.URI.create(url);
                endpoints.put(endpoint.getHost(), endpoint.getPort());
            }
        });
        assertThat(endpoints).containsOnly(Map.entry("lookup", 9001), Map.entry("ledger", 9002), Map.entry("api-catalog", 8090));

        // The emitters' real renderers over the signed plan (this test classpath registers stand-in emitter backends).
        com.unfurl.deployment.plan.BindingPlan plan = CliTestFixtures.readCompiled(composition.compiled()).bindingPlan();
        Map<String, Integer> compose = new LinkedHashMap<>();
        YAML.readTree(new com.unfurl.deploy.compose.ComposeFileRenderer().render(plan, null)).path("services").fields()
                .forEachRemaining(service -> compose.put(service.getKey(), service.getValue().at("/expose/0").asInt(-1)));
        // The catalog declares a composition mount, so Kubernetes needs its part: assembled by the emitter's own template
        // from the real DCP runtime bundle, bindings and public keys (HLD-E §4.0d).
        Path bundle = dir.resolve("bundle.zip");
        CliTestFixtures.CliRun signed = composition.sign(bundle);
        assertThat(signed.exitCode()).withFailMessage(signed.stderr()).isZero();
        Path trust = Files.createDirectories(dir.resolve("trust"));
        SigningTestFixtures.writePublicKeyPem(trust, "signer.pem", composition.keys().getPublic());
        Map<String, String> partInputs = Map.of("dcpRuntimeBundlePath", bundle.toString(),
                "runtimeBindingPath", bindings.toString(), "trustKeysPath", trust.toString());
        Map<String, com.unfurl.deploy.spi.CompositionPart> parts = new LinkedHashMap<>();
        new com.unfurl.deploy.spi.DeclaredCompositionPart(entry -> entry.deploymentShape() == DeploymentShape.CONTAINERIZED_SERVICE)
                .assemble(plan, partInputs).forEach(part -> parts.put(part.serviceName(), part));
        assertThat(parts).containsOnlyKeys("api-catalog");
        assertThat(parts.get("api-catalog").files().keySet()).as("the signed set, bindings, keys, claims and artifacts")
                .contains("contracts/root.frozen.json", "runtime-binding.yaml", "trust-keys/signer.pem",
                        "claims/urn-unfurl-test-lookup.claim.json")
                .anyMatch(path -> path.startsWith("artifacts/sha256/"));
        String manifests = new com.unfurl.deploy.k8s.ManifestRenderer().render(plan, parts);
        assertThat(manifests).contains("name: api-catalog-composition-1", "path: \"/health/ready\"");
        assertThat(new com.unfurl.deploy.compose.ComposeFileRenderer().render(plan, null))
                .contains("      - ./api-catalog-composition:/opt/unfurl/api-catalog/composition:ro\n");
        Map<String, Integer> kubernetes = new LinkedHashMap<>();
        for (JsonNode document : YAML.readValues(YAML.getFactory().createParser(manifests), JsonNode.class).readAll()) {
            if ("Service".equals(document.path("kind").asText())) {
                kubernetes.put(document.at("/metadata/name").asText(), document.at("/spec/ports/0/port").asInt());
                assertThat(document.at("/spec/ports/0/targetPort").asInt()).isEqualTo(document.at("/spec/ports/0/port").asInt());
            }
        }
        endpoints.forEach((host, port) -> {
            assertThat(compose).as("Compose renders " + host).containsEntry(host, port);
            assertThat(kubernetes).as("Kubernetes renders " + host).containsEntry(host, port);
        });

        // How the catalog runs reaches the signed plan with its service, and no other component gains either value.
        assertThat(plan.entries()).filteredOn(entry -> entry.componentId().contains("api-catalog"))
                .isNotEmpty().allSatisfy(entry -> {
                    com.unfurl.deployment.domain.ServiceEndpoint service = entry.service().orElseThrow();
                    assertThat(service.readinessPath()).contains("/health/ready");
                    assertThat(service.compositionMount()).contains(new com.unfurl.deployment.domain.CompositionMount(
                            "/opt/unfurl/api-catalog/composition", "UNFURL_CATALOG_COMPOSITION", List.of("claims", "artifacts")));
                });
        assertThat(plan.entries()).filteredOn(entry -> !entry.componentId().contains("api-catalog"))
                .allSatisfy(entry -> assertThat(entry.service()).hasValueSatisfying(service -> {
                    assertThat(service.readinessPath()).isEmpty();
                    assertThat(service.compositionMount()).isEmpty();
                }));
    }

    /**
     * The scan refuses a readiness path or composition mount without a declared service, and malformed values, by the
     * plan's own rules; each entry is skipped as a malformed manifest naming the field, and a valid declaration is kept.
     */
    @Test
    void scanRefusesHowAServiceRunsWithoutAValidService(@TempDir Path dir) throws Exception {
        Path catalog = Files.createDirectories(dir.resolve("catalog"));
        String valid = manifest("valid", "valid.run", "REMOTE_HTTP", "valid:9100", "", "");
        writeJar(catalog.resolve("valid.jar"), Map.of(MANIFEST, withServiceRun(valid, "/ready", CATALOG_MOUNT)));
        String unserved = manifest("unserved", "unserved.run", "REMOTE_HTTP", null, "", "");
        writeJar(catalog.resolve("probe.jar"), Map.of(MANIFEST, withServiceRun(unserved, "/ready", null)));
        writeJar(catalog.resolve("mount.jar"), Map.of(MANIFEST, withServiceRun(unserved, null, CATALOG_MOUNT)));
        String served = manifest("served", "served.run", "REMOTE_HTTP", "served:9101", "", "");
        writeJar(catalog.resolve("path.jar"), Map.of(MANIFEST, withServiceRun(served, "ready?now", null)));
        writeJar(catalog.resolve("include.jar"), Map.of(MANIFEST, withServiceRun(served, null,
                CATALOG_MOUNT.replace("[claims, artifacts]", "[jars]"))));

        CatalogScanReport report = new CatalogScanner().scan(catalog);

        assertThat(report.catalog().entries()).singleElement().satisfies(entry -> assertThat(entry.metadata().serviceEndpoint())
                .hasValueSatisfying(service -> {
                    assertThat(service.readinessPath()).contains("/ready");
                    assertThat(service.compositionMount().orElseThrow().include()).containsExactly("artifacts", "claims");
                }));
        assertThat(malformed(report, "probe.jar")).contains("binding.readiness_path is declared only with binding.service_name");
        assertThat(malformed(report, "mount.jar")).contains("composition_mount is declared only with binding.service_name");
        assertThat(malformed(report, "path.jar")).contains("binding.readiness_path must be an absolute URL path");
        assertThat(malformed(report, "include.jar")).contains("composition_mount.include allows only");
    }

    /** The aliases name Flow's and Foundry's claim URIs; an alias and an explicit value for one provider are refused. */
    @Test
    void aliasesMapToFlowAndFoundryAndNeverCompete() {
        assertThat(ServiceBaseUrls.parse(List.of(), "http://flow:8080/", "http://foundry:7979"))
                .containsEntry(ServiceBaseUrls.FLOW_CLAIM_URI, "http://flow:8080")
                .containsEntry(ServiceBaseUrls.FOUNDRY_CLAIM_URI, "http://foundry:7979");
        assertThatThrownBy(() -> ServiceBaseUrls.parse(List.of(ServiceBaseUrls.FLOW_CLAIM_URI + "=http://a:1"), "http://b:2", null))
                .hasMessageContaining("more than one base URL");
        assertThatThrownBy(() -> ServiceBaseUrls.parse(List.of("urn:x=ftp://a"), null, null)).hasMessageContaining("http(s)");
    }

    // -------------------------------------------------------------------------------------- §4.2, §4.3 settings, gate

    /**
     * With an API catalog in the composition, DCP's gap gate runs first: an undocumented capability fails with
     * DOCUMENTATION_GAP naming it; marking it private passes, and the catalog's binding records every setting, defaults
     * included; given settings are written as given. Without an API catalog the settings are refused, not dropped.
     */
    /**
     * §4.4: {@code --disable-binding} writes only the matched binding with {@code runtime_policy.enabled: false}, leaving
     * every other binding, the root and the gap gate as they were. A malformed key, a key matching no child contract and an
     * identity key are each refused with {@code BINDING_DISABLE_INVALID}, and nothing is written.
     */
    @Test
    void anOperatorDisablesOneBindingAndNothingElse(@TempDir Path dir) throws Exception {
        Composition composition = Composition.documented(dir, true);
        Path visibility = Files.writeString(dir.resolve("visibility.yaml"), "capabilities:\n  ledger.post: private\n");

        Path out = dir.resolve("bindings.yaml");
        CliTestFixtures.CliRun ok = composition.bindings(out, "--documentation-visibility", visibility.toString(),
                "--disable-binding", LOOKUP_URI + "#lookup.find");
        assertThat(ok.exitCode()).withFailMessage(ok.stderr()).as("the gap gate still passes").isZero();
        JsonNode set = YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set");
        assertThat(binding(set, "lookup.find").at("/runtime_policy/enabled").asBoolean(true)).isFalse();
        assertThat(binding(set, "lookup.find").at("/lifecycle/enabled").asBoolean()).as("only the runtime policy changes").isTrue();
        assertThat(binding(set, "ledger.post").at("/runtime_policy/enabled").asBoolean()).isTrue();
        assertThat(binding(set, "documentation.catalog").at("/runtime_policy/enabled").asBoolean()).isTrue();
        assertThat(StreamSupport.stream(set.path("runtime_bindings").spliterator(), false)
                .filter(binding -> !binding.at("/runtime_policy/enabled").asBoolean()).count())
                .as("exactly one binding is disabled").isEqualTo(1);

        record Refusal(String key, String reason) {
        }
        for (Refusal refusal : List.of(
                new Refusal("not-a-key", "must be <provider-claim-uri>#<capability>"),
                new Refusal(LOOKUP_URI + "#ledger.post", "matches no child contract"),
                new Refusal("urn:unfurl:keycloak#identity-context", "identity is used fully while it is in the contract"))) {
            Path refusedOut = dir.resolve("refused-" + Math.abs(refusal.key().hashCode()) + ".yaml");
            CliTestFixtures.CliRun refused = composition.bindings(refusedOut, "--documentation-visibility", visibility.toString(),
                    "--disable-binding", refusal.key());
            assertThat(refused.exitCode()).as(refusal.key()).isNotZero();
            assertThat(refused.stderr()).contains("BINDING_DISABLE_INVALID", refusal.reason());
            assertThat(refusedOut).as("nothing is written").doesNotExist();
        }
    }

    @Test
    void anApiCatalogGetsItsSettingsBehindTheGapGate(@TempDir Path dir) throws Exception {
        Composition composition = Composition.documented(dir, true);

        CliTestFixtures.CliRun gap = composition.bindings(dir.resolve("gap.yaml"));
        assertThat(gap.exitCode()).isNotZero();
        assertThat(gap.stderr()).contains("DOCUMENTATION_GAP", "capability ledger.post provider " + LEDGER_URI)
                .doesNotContain("capability lookup.find");

        Path visibility = Files.writeString(dir.resolve("visibility.yaml"), "capabilities:\n  ledger.post: private\n");
        Path out = dir.resolve("bindings.yaml");
        CliTestFixtures.CliRun ok = composition.bindings(out, "--documentation-visibility", visibility.toString());
        assertThat(ok.exitCode()).withFailMessage(ok.stderr()).isZero();
        JsonNode values = binding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"), "documentation.catalog")
                .at("/configuration/values");
        assertThat(values.at("/documentation_visibility/default").asText()).isEqualTo("internal");
        assertThat(values.at("/documentation_visibility/capabilities/ledger.post").asText()).isEqualTo("private");
        assertThat(values.at("/documentation_visibility/api_documents").isObject()).isTrue();
        assertThat(values.at("/observation/interval_ms").asLong()).isEqualTo(10_000);
        assertThat(values.at("/observation/max_backoff_ms").asLong()).isEqualTo(60_000);
        assertThat(binding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"), "lookup.find")
                .at("/configuration/values/documentation_visibility").isMissingNode()).as("only the catalog's binding").isTrue();

        Path tuned = dir.resolve("tuned.yaml");
        assertThat(composition.bindings(tuned, "--documentation-visibility", visibility.toString(),
                "--documentation-visibility-default", "public", "--catalog-observation-interval-ms", "5000",
                "--catalog-observation-max-backoff-ms", "20000").exitCode()).isZero();
        JsonNode tunedValues = binding(YAML.readTree(Files.readAllBytes(tuned)).path("runtime_binding_set"), "documentation.catalog")
                .at("/configuration/values");
        assertThat(tunedValues.at("/documentation_visibility/default").asText()).isEqualTo("public");
        assertThat(tunedValues.at("/observation/interval_ms").asLong()).isEqualTo(5000);
        assertThat(composition.bindings(dir.resolve("bad.yaml"), "--catalog-observation-interval-ms", "5000",
                "--catalog-observation-max-backoff-ms", "100").stderr()).contains("must be at least the observation interval");
        // The catalog's own minimum, so every binding Fabric writes starts it.
        assertThat(composition.bindings(dir.resolve("fast.yaml"), "--catalog-observation-interval-ms", "500").stderr())
                .contains("--catalog-observation-interval-ms must be at least 1000");

        Composition withoutCatalog = Composition.documented(dir.resolve("plain"), false);
        CliTestFixtures.CliRun refused = withoutCatalog.bindings(dir.resolve("plain.yaml"), "--documentation-visibility-default", "public");
        assertThat(refused.exitCode()).isNotZero();
        assertThat(refused.stderr()).contains("DOCUMENTATION_SETTINGS_WITHOUT_CATALOG", "--documentation-visibility-default");
        assertThat(withoutCatalog.bindings(dir.resolve("plain-ok.yaml")).exitCode()).as("no catalog, no settings: no gate").isZero();
    }

    // ------------------------------------------------------------------------------------------ §6 documentation-check

    /** {@code documentation-check} writes its report and no binding: GAP exits 1 naming the gap, PASS exits 0. */
    @Test
    void documentationCheckReportsGapsWithoutWritingBindings(@TempDir Path dir) throws Exception {
        Composition composition = Composition.documented(dir, true);
        Path report = dir.resolve("step-18-documentation-check-report.json");

        CliTestFixtures.CliRun gap = composition.run("documentation-check", report);
        assertThat(gap.exitCode()).isEqualTo(1);
        JsonNode gapReport = JSON.readTree(Files.readAllBytes(report));
        assertThat(gapReport.path("status").asText()).isEqualTo("GAP");
        assertThat(gapReport.at("/gaps/0/capability").asText()).isEqualTo("ledger.post");
        assertThat(gapReport.at("/gaps/0/provider").asText()).isEqualTo(LEDGER_URI);
        assertThat(StreamSupport.stream(gapReport.path("components").spliterator(), false)
                .filter(c -> c.path("claimUri").asText().equals(LOOKUP_URI)).findFirst().orElseThrow()
                .path("documented").get(0).asText()).isEqualTo("lookup.find");

        Path visibility = Files.writeString(dir.resolve("visibility.yaml"), "capabilities:\n  ledger.post: private\n");
        CliTestFixtures.CliRun pass = composition.run("documentation-check", report, "--documentation-visibility", visibility.toString());
        assertThat(pass.exitCode()).withFailMessage(pass.stderr()).isZero();
        assertThat(JSON.readTree(Files.readAllBytes(report)).path("status").asText()).isEqualTo("PASS");
        assertThat(pass.stdout()).contains("documentationCheck=PASS");
    }

    // ------------------------------------------------------------------------------------------------- §5 identity

    /**
     * Keycloak's identity-context is an external_protocol (OAuth 2.0) offer, so with an API catalog in the composition it
     * is never a documentation gap, whether Keycloak is bundled as a container or the customer runs it (external SaaS);
     * its binding never gets a DCP URL; and the check lists it as external.
     */
    @Test
    void keycloakIsNeverAGapInEitherShape(@TempDir Path dir) throws Exception {
        for (boolean external : new boolean[]{false, true}) {
            Path root = Files.createDirectories(dir.resolve(external ? "external" : "container"));
            Composition composition = Composition.identity(root, external);
            Path visibility = Files.writeString(root.resolve("visibility.yaml"), "capabilities:\n  agent.run: private\n");
            Path out = root.resolve("bindings.yaml");

            CliTestFixtures.CliRun ok = composition.bindings(out, concat(identityFlags(), "--documentation-visibility", visibility.toString()));

            assertThat(ok.exitCode()).as(external ? "external" : "container").withFailMessage(ok.stderr()).isZero();
            JsonNode identity = binding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"), "identity-context");
            assertThat(identity.at("/provider_instance/deployment_kind").asText())
                    .isEqualToIgnoringCase(external ? "external_saas" : "container");
            assertThat(identity.at("/provider_instance/base_url").isMissingNode()).isTrue();
            Path report = root.resolve("report.json");
            assertThat(composition.run("documentation-check", report, concat(identityFlags(), "--documentation-visibility",
                    visibility.toString())).exitCode()).isZero();
            assertThat(StreamSupport.stream(JSON.readTree(Files.readAllBytes(report)).path("components").spliterator(), false)
                    .filter(c -> c.path("claimUri").asText().equals(CliTestFixtures.IDENTITY_PROVIDER_URI)).findFirst()
                    .orElseThrow().path("external").get(0).asText()).isEqualTo("identity-context");
        }
    }

    // ---------------------------------------------------------------------------------------------------- fixtures

    private static final String MANIFEST = "META-INF/unfurl-catalog.yaml";

    /**
     * Fixture: a compiled composition over a catalog of jars, and the commands that run against it.
     */
    private record Composition(Path dir, Path catalog, Path compiled, Path signed, KeyPair keys) {
        /**
         * {@code lookup.find} (documented, REMOTE_HTTP, service lookup:9001) and {@code ledger.post} (undocumented,
         * REMOTE_HTTP, service ledger:9002), plus, when {@code withCatalog}, an API catalog (documented, REMOTE_HTTP,
         * service api-catalog:8090).
         */
        static Composition documented(Path dir, boolean withCatalog) throws Exception {
            Path catalog = Files.createDirectories(dir.resolve("catalog"));
            writeJar(catalog.resolve("lookup.jar"), documentedJar("lookup", "lookup.find", "REMOTE_HTTP", "lookup:9001",
                    SchemaFiles.valid("lookup.find")));
            writeJar(catalog.resolve("ledger.jar"), Map.of(MANIFEST, manifest("ledger", "ledger.post", "REMOTE_HTTP", "ledger:9002", "", "")));
            List<String> needs = new ArrayList<>(List.of("lookup.find", "ledger.post"));
            if (withCatalog) {
                writeJar(catalog.resolve("api-catalog.jar"), apiCatalogJar());
                needs.add("documentation.catalog");
            }
            return compile(dir, catalog, needs, null);
        }

        /**
         * {@code lookup.find} (service lookup:9001), {@code ledger.post} (REMOTE_HTTP, no service) and
         * {@code token.issue} (REMOTE_HTTP, no service, external_protocol): the endpoint rules' three cases.
         */
        static Composition endpoints(Path dir) throws Exception {
            Path catalog = Files.createDirectories(dir.resolve("catalog"));
            writeJar(catalog.resolve("lookup.jar"), documentedJar("lookup", "lookup.find", "REMOTE_HTTP", "lookup:9001",
                    SchemaFiles.valid("lookup.find")));
            writeJar(catalog.resolve("ledger.jar"), Map.of(MANIFEST, manifest("ledger", "ledger.post", "REMOTE_HTTP", null, "", "")));
            writeJar(catalog.resolve("tokens.jar"), Map.of(MANIFEST, manifest("tokens", "token.issue", "REMOTE_HTTP", null,
                    interfaceYaml("EXTERNAL_PROTOCOL", "protocol: oauth2"), "")));
            return compile(dir, catalog, List.of("lookup.find", "ledger.post", "token.issue"), null);
        }

        /**
         * The Foundry stand-in (agent.run, depends on identity), the Keycloak stand-in in its container or external shape,
         * and an API catalog.
         */
        static Composition identity(Path dir, boolean external) throws Exception {
            Path catalog = Files.createDirectories(dir.resolve("catalog"));
            CliTestFixtures.writeFoundryJar(catalog, "agent.jar");
            CliTestFixtures.writeIdentityProviderJar(catalog);
            writeJar(catalog.resolve("api-catalog.jar"), apiCatalogJar());
            Path policy = null;
            if (external) {
                // Prefer the customer's Keycloak; the API catalog still runs as its container (its only shape).
                policy = Files.writeString(dir.resolve("deployment-policy.yaml"), """
                        preferredShapes: [MANAGED_EXTERNAL_ADAPTER, IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                        disallowedShapes: []
                        requireIsolationForCapabilityPatterns: []
                        runtime:
                          springBoot: true
                          kubernetes: true
                          serviceMesh: true
                        """);
            }
            return compile(dir, catalog, List.of("agent.run", "identity-context", "documentation.catalog"), policy);
        }

        /** Compiles the needs over the catalog and signs the result. */
        private static Composition compile(Path dir, Path catalog, List<String> capabilities, Path policy) throws Exception {
            StringBuilder needs = new StringBuilder("requiredCapabilities:\n");
            capabilities.forEach(capability -> needs.append("  - capability: ").append(capability).append("\n    capabilityVersion: ^1\n"));
            Path needsFile = Files.writeString(dir.resolve("needs.yaml"), needs);
            Path compiled = dir.resolve("compiled.yaml");
            List<String> argv = new ArrayList<>(List.of("compile", "--catalog", catalog.toString(), "--needs", needsFile.toString(),
                    "--out", compiled.toString()));
            if (policy != null) argv.addAll(List.of("--deployment-policy", policy.toString()));
            CliTestFixtures.CliRun compile = CliTestFixtures.run(argv.toArray(String[]::new));
            if (compile.exitCode() != 0) throw new AssertionError(compile.stderr());
            KeyPair keys = SigningTestFixtures.generateEcKeyPair();
            Composition composition = new Composition(dir, catalog, compiled, dir.resolve("signed.yaml"), keys);
            CliTestFixtures.CliRun sign = composition.sign(null);
            if (sign.exitCode() != 0) throw new AssertionError(sign.stderr());
            return composition;
        }

        /** Signs the compiled contract to {@link #signed}, writing the DCP runtime bundle too when {@code bundle} is given. */
        CliTestFixtures.CliRun sign(Path bundle) throws IOException {
            List<String> argv = new ArrayList<>(List.of("sign", "--contract", compiled.toString(),
                    "--key", SigningTestFixtures.writePrivateKeyPem(dir, "key.pem", keys.getPrivate()).toString(),
                    "--public-key", SigningTestFixtures.writePublicKeyPem(dir, "pub.pem", keys.getPublic()).toString(),
                    "--out", (bundle == null ? signed : dir.resolve("resigned.yaml")).toString()));
            if (bundle != null) argv.addAll(List.of("--dcp-runtime-bundle", bundle.toString(), "--catalog", catalog.toString()));
            return CliTestFixtures.run(argv.toArray(String[]::new));
        }

        /** Runs {@code runtime-bindings} for tenant acme into {@code out}, with extra flags. */
        CliTestFixtures.CliRun bindings(Path out, String... extra) {
            return run("runtime-bindings", out, extra);
        }

        /** Runs a binding command ({@code runtime-bindings} or {@code documentation-check}) with the shared flags. */
        CliTestFixtures.CliRun run(String command, Path out, String... extra) {
            List<String> argv = new ArrayList<>(List.of(command, "--signed-contract", signed.toString(), "--catalog", catalog.toString(),
                    "--out", out.toString(), "--tenant", "acme", "--environment", "production"));
            argv.addAll(List.of(extra));
            return CliTestFixtures.run(argv.toArray(String[]::new));
        }
    }

    /**
     * The API catalog stand-in: urn:unfurl:api-catalog offering documented documentation.catalog, served at
     * api-catalog:8090, with the real catalog's readiness path and composition mount (HLD-E §4.0d).
     */
    private static Map<String, byte[]> apiCatalogJar() {
        SchemaFiles files = SchemaFiles.valid("documentation.catalog");
        Map<String, byte[]> entries = new LinkedHashMap<>(files.files);
        entries.put(MANIFEST, withServiceRun(manifest("api-catalog", "documentation.catalog", "REMOTE_HTTP", "api-catalog:8090",
                files.documentationYaml(), CATALOG_URI), "/health/ready", CATALOG_MOUNT).getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    /** The API catalog's {@code composition_mount}, as its packaged catalog block declares it, at catalog-field depth. */
    private static final String CATALOG_MOUNT = """
              composition_mount:
                path: /opt/unfurl/api-catalog/composition
                env: UNFURL_CATALOG_COMPOSITION
                include: [claims, artifacts]
            """;

    /**
     * Adds how the service runs to a {@link #manifest}: a {@code binding.readiness_path} after its binding modes, and a
     * {@code composition_mount} block at the end of the catalog block; either may be null.
     */
    private static String withServiceRun(String manifest, String readinessPath, String mount) {
        String withPath = readinessPath == null ? manifest : manifest.replaceFirst("(?m)^(    supported_modes: \\[[A-Z_]+\\])$",
                "$1\n    readiness_path: " + readinessPath);
        return mount == null ? withPath : withPath + mount;
    }

    /** A documented component jar: its manifest plus the schema files it names. */
    private static Map<String, byte[]> documentedJar(String artifact, String capability, String mode, String service, SchemaFiles files) {
        Map<String, byte[]> entries = new LinkedHashMap<>(files.files);
        entries.put(MANIFEST, manifest(artifact, capability, mode, service, files.documentationYaml(), "").getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    /**
     * The schema files of one capability and its claim's {@code documentation} block, pinned by the bytes. The response
     * schema is {@link #SHARED_RESPONSE} for every capability.
     */
    private static final class SchemaFiles {
        final String capability;
        final String requestPath;
        final String responsePath;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final String requestSha;
        final String responseSha;

        private SchemaFiles(String capability, String request) {
            this.capability = capability;
            this.requestPath = "dcp/schemas/" + capability + ".request.json";
            this.responsePath = "dcp/schemas/" + capability + ".response.json";
            byte[] requestBytes = request.getBytes(StandardCharsets.UTF_8);
            byte[] responseBytes = SHARED_RESPONSE.getBytes(StandardCharsets.UTF_8);
            files.put(requestPath, requestBytes);
            files.put(responsePath, responseBytes);
            requestSha = sha256(requestBytes);
            responseSha = sha256(responseBytes);
        }

        /** Valid schemas: a request naming the capability, the shared response. */
        static SchemaFiles valid(String capability) {
            return of(capability, "{\"type\":\"object\",\"properties\":{\"" + capability + "\":{\"type\":\"string\"}}}");
        }

        /** The given request schema text and the shared response. */
        static SchemaFiles of(String capability, String request) {
            return new SchemaFiles(capability, request);
        }

        /** The offer's {@code documentation} block, at the offer's field depth (six spaces), on new lines. */
        String documentationYaml() {
            return "\n" + documentationLines().stripTrailing();
        }

        private String documentationLines() {
            return """
                      documentation:
                        summary: %s
                        operations:
                          - operation: %s
                            request_schema: {path: %s, media_type: application/schema+json, sha256: %s}
                            response_schema: {path: %s, media_type: application/schema+json, sha256: %s}
                """.formatted(capability, capability, requestPath, requestSha, responsePath, responseSha);
        }
    }

    /** An {@code offer_interface} block at the offer's field depth. */
    private static String interfaceYaml(String kind, String detail) {
        return "\n      offer_interface:\n        interface_kind: " + kind + "\n        details:\n          " + detail;
    }

    /**
     * A schema-valid one-offer manifest.
     *
     * @param mode       the catalog binding mode
     * @param service    {@code name:port} the component listens on, or null
     * @param offerExtra extra offer fields (interface, documentation), each line already at the offer's field depth
     * @param uri        the claim URI, or empty for {@code urn:unfurl:test:<artifact>}
     */
    private static String manifest(String artifact, String capability, String mode, String service, String offerExtra, String uri) {
        String claimUri = uri == null || uri.isEmpty() ? "urn:unfurl:test:" + artifact : uri;
        // A declared service is placed as a container, so the plan carries it to the bindings and the emitter (HLD-D §6.2b).
        String serviceYaml = service == null ? "" : "\n    service_name: " + service.split(":")[0]
                + "\n    service_port: " + service.split(":")[1]
                + "\n  component_shape_profile:\n    default_shape: CONTAINERIZED_SERVICE"
                + "\n    supported_shapes: [CONTAINERIZED_SERVICE]\n    shape_runtime: {}";
        return """
                claim:
                  identity:
                    uri: %s
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
                      metered: false%s
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
                    default_mode: %s
                    supported_modes: [%s]%s
                """.formatted(claimUri, artifact, artifact, capability, capability, capability, capability, offerExtra,
                artifact, mode, mode, serviceYaml);
    }

    /** Complete identity inputs; the audience names the Foundry stand-in, the composition's protected service. */
    private static String[] identityFlags() {
        return new String[]{
                "--identity-issuer", "https://idp.example.com/realms/acme",
                "--identity-audiences", "urn:unfurl:test:agent.run=foundry",
                "--identity-tenant-claim", "tenant_id",
                "--identity-roles-claim", "roles",
                "--identity-permissions-claim", "permissions",
                "--identity-token-endpoint-ref", "config://acme/identity/token-endpoint",
                "--identity-verification-keys-ref", "config://acme/identity/verification-keys",
                "--identity-client-credentials-ref", "secret://acme/identity/flow-client"};
    }

    private static String[] concat(String[] first, String... more) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    /** The single binding whose configuration names this capability. */
    private static JsonNode binding(JsonNode set, String capability) {
        List<JsonNode> matches = StreamSupport.stream(set.path("runtime_bindings").spliterator(), false)
                .filter(binding -> capability.equals(binding.at("/configuration/values/capability").asText())).toList();
        assertThat(matches).as(capability).hasSize(1);
        return matches.get(0);
    }

    /** The detail of the malformed-manifest skip recorded for a jar. */
    private static String malformed(CatalogScanReport report, String jar) {
        SkippedEntry skip = report.skippedEntries().stream().filter(s -> s.jarPath().getFileName().toString().equals(jar))
                .findFirst().orElseThrow(() -> new AssertionError(jar + " was not skipped: " + report.skippedEntries()));
        assertThat(skip.reason()).isEqualTo(SkippedEntry.SkipReason.MALFORMED_MANIFEST);
        return skip.detail();
    }

    /** The detail of the skip recorded for a jar. */
    private static String skip(CatalogScanReport report, String jar) {
        SkippedEntry skip = report.skippedEntries().stream().filter(s -> s.jarPath().getFileName().toString().equals(jar))
                .findFirst().orElseThrow(() -> new AssertionError(jar + " was not skipped: " + report.skippedEntries()));
        assertThat(skip.reason()).isEqualTo(SkippedEntry.SkipReason.DOCUMENTATION_ARTIFACT_INVALID);
        return skip.detail();
    }

    private static void writeJar(Path file, Map<String, ?> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, ?> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                Object value = entry.getValue();
                jar.write(value instanceof byte[] bytes ? bytes : value.toString().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }

    private static Map<String, byte[]> unzip(Path zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static String sha256(byte[] bytes) {
        return ArtifactRef.sha256Of(bytes);
    }
}
