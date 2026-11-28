package com.unfurl.fabric.studio;

import com.unfurl.fabric.catalog.CatalogManifestCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Test fixture: a Studio asset root holding two real components, so Studio tests run against honest catalog entries
 * (LLD-fabric-studio-api, What a catalog entry is). Fabric ships no built-in entries, so a test that needs a catalog
 * builds one here.
 *
 * <p>Each component is a jar with a DCP claim in {@code META-INF/unfurl-catalog.yaml}, scanned like any jar in an asset
 * root:
 * <ul>
 *   <li>{@code com.unfurl:validation-service:1.1.0} offers {@code validate.order}, {@code validate.payment} and
 *       {@code validate.inventory};</li>
 *   <li>{@code com.unfurl:storage-s3:1.2.0} offers {@code storage.put}.</li>
 * </ul>
 * A package visual manifest decorates both with a fallback shape, compatible descendants and a model and thumbnail whose
 * bytes are real files, pinned by the hashes Fabric computes.
 *
 * <p>Factory: {@link #service()} and {@link #service(StudioStateStore)} build a catalog service over the fixture root. The
 * root is written once per test JVM and never changed, so tests may share it.
 */
final class StudioTestCatalog {

    /** The validation component's catalog entry id. */
    static final String VALIDATION_SERVICE = "com.unfurl:validation-service:1.1.0";
    /** The storage component's catalog entry id. */
    static final String STORAGE_S3 = "com.unfurl:storage-s3:1.2.0";

    private static Path root;

    private StudioTestCatalog() {
    }

    /** A catalog service over the fixture asset root, without persisted state. */
    static StudioCatalogService service() {
        return new StudioCatalogService(null, assetRoot());
    }

    /** A catalog service over the fixture asset root, persisting to {@code store}. */
    static StudioCatalogService service(StudioStateStore store) {
        return new StudioCatalogService(store, assetRoot());
    }

    /** The fixture asset root, written on first use. */
    static synchronized Path assetRoot() {
        if (root == null) {
            try {
                Path dir = Files.createTempDirectory("studio-test-catalog");
                writeInto(dir);
                root = dir;
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot write the Studio test catalog", ex);
            }
        }
        return root;
    }

    /**
     * Writes the two component jars and their package visuals into {@code dir}, for a test that needs its own asset root
     * (for example one that adds more jars beside them).
     */
    static void writeInto(Path dir) throws IOException {
        writeJar(dir.resolve("validation-service.jar"), manifest("validation-service", "1.1.0",
                List.of("validate.order", "validate.payment", "validate.inventory")));
        writeJar(dir.resolve("storage-s3.jar"), manifest("storage-s3", "1.2.0", List.of("storage.put")));
        Path visuals = dir;
        Files.createDirectories(visuals.resolve("META-INF/visual"));
        for (String slug : List.of("validation-service", "storage-s3")) {
            Files.writeString(visuals.resolve("META-INF/visual/" + slug + ".glb"), "glTF model bytes for " + slug, StandardCharsets.UTF_8);
            Files.writeString(visuals.resolve("META-INF/visual/" + slug + "-thumbnail.png"), "png bytes for " + slug, StandardCharsets.UTF_8);
        }
        Files.writeString(visuals.resolve(StudioPackageVisualAssets.MANIFEST_PATH), """
                {
                  "entries": [
                    %s,
                    %s
                  ]
                }
                """.formatted(
                visual(VALIDATION_SERVICE, "validation-service", "WORKFLOW",
                        List.of("component.customer-policy-validator", "component.fraud-validator")),
                visual(STORAGE_S3, "storage-s3", "STORAGE", List.of("component.azure-blob", "component.minio-storage"))),
                StandardCharsets.UTF_8);
    }

    /** One package visual entry: presentation only; the claim comes from the jar. */
    private static String visual(String entryId, String slug, String category, List<String> descendants) {
        return """
                {
                  "catalogEntryId": "%s",
                  "visualManifest": {
                    "fallbackShape": { "kind": "CUBE", "category": "%s" },
                    "interactions": { "draggable": true, "connectable": true, "inspectable": true }
                  },
                  "dynamicComposition": { "dcpType": "COMPONENT", "compatibleDescendants": [%s] },
                  "assets": [
                    { "assetId": "%s-model", "path": "META-INF/visual/%s.glb", "mediaType": "model/gltf-binary" },
                    { "assetId": "%s-thumbnail", "path": "META-INF/visual/%s-thumbnail.png", "mediaType": "image/png" }
                  ]
                }""".formatted(entryId, category,
                String.join(", ", descendants.stream().map(id -> "\"" + id + "\"").toList()), slug, slug, slug, slug);
    }

    /** A valid DCP claim manifest offering {@code capabilities}, as a real component jar carries it. */
    static String manifest(String name, String version, List<String> capabilities) {
        return manifest(name, version, capabilities, List.of());
    }

    /**
     * A valid DCP claim manifest offering {@code capabilities} and needing {@code needs} (DCP need URIs such as
     * {@code validate.order@v1}), as a real component jar carries it.
     */
    static String manifest(String name, String version, List<String> capabilities, List<String> needs) {
        return manifest(name, version, capabilities, needs, Map.of());
    }

    /**
     * A valid DCP claim manifest for an aggregate component (DCP HLD-C2 A.14): it also names its {@code child_claims},
     * each child's claim URI mapped to whether it is required. The test components' claim URIs are
     * {@code urn:unfurl:test:<name>}.
     */
    static String manifest(String name, String version, List<String> capabilities, List<String> needs,
                           Map<String, Boolean> children) {
        StringBuilder childClaims = new StringBuilder();
        if (!children.isEmpty()) {
            childClaims.append("  child_claims:\n");
            children.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(child -> childClaims
                    .append("    - claim_uri: ").append(child.getKey()).append('\n')
                    .append("      required: ").append(child.getValue()).append('\n'));
        }
        StringBuilder needed = new StringBuilder();
        for (String need : needs) {
            needed.append("      - \"").append(need).append("\"\n");
        }
        StringBuilder offers = new StringBuilder();
        for (String capability : capabilities) {
            offers.append("    - capability: ").append(capability).append('\n')
                    .append("      description: ").append(capability).append('\n')
                    .append("      consumer_access: ANY\n")
                    .append("      stability: STABLE\n")
                    .append("      version: ").append(version).append('\n')
                    .append("      metered: false\n");
        }
        return """
                claim:
                  identity:
                    uri: urn:unfurl:test:%s
                    name: %s
                    kind: COMPONENT
                    version: %s
                    publisher: Unfurl
                  domain:
                    summary: %s
                    concerns:
                      - concern: %s
                        description: %s test component
                    boundary_principles:
                      - test boundary
                  refusals:
                    - concern: unrelated.concern
                      rationale: This component deliberately owns only its declared capabilities.
                      owned_by: host
                  dependencies:
                    needs:%s
                  offers:
                %s
                  integration_ports:
                    ports: {}
                  faults:
                    emitted: []
                %s  metadata:
                    dcp_version: 0.2.0
                    claim_version: 1.0.0
                    created_at: 1970-01-01T00:00:00Z
                catalog:
                  lifecycle:
                    status: ACTIVE
                  artifact:
                    coordinates: com.unfurl:%s:%s
                    packaging: jar
                    source: catalog
                  binding:
                    default_mode: IN_PROCESS
                    supported_modes: [IN_PROCESS]
                  component_shape_profile:
                    default_shape: IN_PROCESS_LIBRARY
                    supported_shapes: [IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE]
                    shape_runtime: {}
                """.formatted(name, name, version, name, name, name,
                needs.isEmpty() ? " []" : "\n" + needed.toString().stripTrailing(), offers, childClaims, name, version);
    }

    /**
     * A catalog entry that carries a claim, for a test that builds a catalog snapshot itself: its manifest is a valid DCP
     * claim named after the entry id, and its claim hash is the one Fabric computes for that claim, as at admission.
     *
     * @param catalogEntryId     the entry id, for example {@code uploaded:flow-0.1.0.jar}.
     * @param visualManifest     palette presentation.
     * @param dynamicComposition projection metadata.
     * @param capabilities       the capabilities the claim offers.
     */
    static StudioVisualCatalogEntry claimedEntry(String catalogEntryId, Map<String, Object> visualManifest,
                                                 Map<String, Object> dynamicComposition, List<String> capabilities) {
        String name = catalogEntryId.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase();
        String manifest = manifest(name, "1.0.0", capabilities.isEmpty() ? List.of(name + ".run") : capabilities);
        CatalogManifestCodec codec = new CatalogManifestCodec();
        String claimHash = "sha256:" + codec.computeClaimHash(codec.parse(manifest.getBytes(StandardCharsets.UTF_8)).claim());
        String artifactSha = CatalogManifestCodec.sha256Hex(manifest.getBytes(StandardCharsets.UTF_8));
        return new StudioVisualCatalogEntry(catalogEntryId, claimHash, "sha256:" + artifactSha, visualManifest,
                dynamicComposition, Map.of(), List.of(), manifest);
    }

    /** Writes a jar carrying {@code manifestYaml} as its catalog manifest. */
    static void writeJar(Path target, String manifestYaml) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("META-INF/unfurl-catalog.yaml"));
            jar.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
