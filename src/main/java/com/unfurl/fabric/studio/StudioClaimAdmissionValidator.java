package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimValidator;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.Severity;
import com.unfurl.fabric.catalog.CatalogManifestCodec;
import com.unfurl.fabric.catalog.CatalogScanException;
import com.unfurl.fabric.catalog.CompanionCoordinates;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adapter: turns a Studio-uploaded artifact into DCP claim-validation results. A claim is admitted only from the
 * artifact that carries it (DCP HLD-C §3.10): the draft must be a {@code .jar} whose bytes are sent as
 * {@code artifactBase64}; Studio reads the jar's {@code META-INF/unfurl-catalog.yaml}, computes the artifact SHA-256
 * itself, and delegates all schema decisions to {@link ClaimValidator} so Fabric admission cannot drift from the
 * protocol library. A detached claim document is refused.
 */
final class StudioClaimAdmissionValidator {
    /** Diagnostic code for a claim sent without the artifact that carries it. */
    static final String CLAIM_DETACHED = "CLAIM_DETACHED";
    /** Diagnostic code for a declared artifact hash that does not match the uploaded bytes. */
    static final String ARTIFACT_HASH_MISMATCH = "ARTIFACT_HASH_MISMATCH";

    private static final String EMBEDDED_CATALOG_MANIFEST_PATH = "META-INF/unfurl-catalog.yaml";

    private final ObjectMapper yamlMapper;
    private final ClaimValidator claimValidator;
    private final CatalogManifestCodec manifestCodec = new CatalogManifestCodec();

    /**
     * Factory constructor: builds the canonical YAML mapper used for Studio uploads and
     * the protocol-owned DCP validator used for all admission decisions.
     */
    StudioClaimAdmissionValidator() {
        this(buildYamlMapper(), new ClaimValidator());
    }

    /**
     * Test seam constructor: allows focused tests to inject mapper/validator instances while
     * preserving the same admission algorithm used in production.
     */
    StudioClaimAdmissionValidator(ObjectMapper yamlMapper, ClaimValidator claimValidator) {
        this.yamlMapper = yamlMapper == null ? buildYamlMapper() : yamlMapper;
        this.claimValidator = claimValidator == null ? new ClaimValidator() : claimValidator;
    }

    /**
     * Strategy method: read the claim out of the uploaded jar and return a verified/rejected result. Every refusal —
     * a detached claim, missing or undecodable bytes, a hash mismatch, a missing or incomplete manifest, an invalid
     * claim — is a DCP-shaped diagnostic so the Studio UI renders all admission failures through one surface.
     */
    AdmissionValidation validate(StudioComponentArtifactDraft artifact) {
        if (artifact == null) {
            return AdmissionValidation.rejected(List.of(error(ErrorCode.CLAIM_MALFORMED.name(), "artifact",
                    "an artifact is required for catalog admission")));
        }
        if (!artifact.claimYaml().isBlank()) {
            return AdmissionValidation.rejected(List.of(error(CLAIM_DETACHED, "claimYaml",
                    "claimYaml is not accepted: a claim is admitted only from the artifact that carries it;"
                            + " send the component's .jar bytes as artifactBase64")));
        }
        if (!artifact.fileName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return AdmissionValidation.rejected(List.of(error(CLAIM_DETACHED, "fileName",
                    "only .jar artifacts can be admitted; a claim is admitted only from the artifact that carries it")));
        }
        if (artifact.artifactBase64().isBlank()) {
            return AdmissionValidation.rejected(List.of(error(ErrorCode.CLAIM_MALFORMED.name(), "artifactBase64",
                    "artifactBase64 is required: send the .jar bytes so Studio can read the claim the jar carries")));
        }
        byte[] artifactBytes;
        try {
            artifactBytes = Base64.getDecoder().decode(artifact.artifactBase64());
        } catch (IllegalArgumentException ex) {
            return AdmissionValidation.rejected(List.of(error(ErrorCode.CLAIM_MALFORMED.name(), "artifactBase64",
                    "JAR artifactBase64 is not valid base64: " + ex.getMessage())));
        }
        String artifactSha = "sha256:" + sha256Hex(artifactBytes);
        if (!artifact.sha256().isBlank() && !normalizedSha(artifact.sha256()).equals(artifactSha)) {
            return AdmissionValidation.rejected(List.of(error(ARTIFACT_HASH_MISMATCH, "sha256",
                    "declared sha256 " + artifact.sha256() + " does not match the uploaded artifact (" + artifactSha + ")")));
        }
        ManifestSource manifest = extractEmbeddedCatalogManifest(artifactBytes);
        if (manifest.absent()) {
            return companionOrRejected(artifactBytes, artifactSha, manifest);
        }
        if (!manifest.diagnostics().isEmpty()) {
            return AdmissionValidation.rejected(manifest.diagnostics());
        }
        Claim claim;
        List<StudioDcpDiagnostic> diagnostics;
        try {
            claim = parseClaim(manifest.manifestYaml());
            diagnostics = claimValidator.validate(claim).diagnostics().stream()
                    .map(StudioClaimAdmissionValidator::toStudioDiagnostic)
                    .toList();
        } catch (IOException | RuntimeException ex) {
            return AdmissionValidation.rejected(List.of(error(ErrorCode.CLAIM_MALFORMED.name(), "claim",
                    "unable to parse DCP claim YAML: " + ex.getMessage())));
        }
        if (diagnostics.stream().anyMatch(diagnostic -> Severity.ERROR.name().equals(diagnostic.severity()))) {
            return AdmissionValidation.rejected(diagnostics);
        }
        try {
            // The catalog block is required too: Studio never fills in lifecycle, artifact or binding metadata.
            manifestCodec.parse(manifest.manifestYaml().getBytes(StandardCharsets.UTF_8));
        } catch (CatalogScanException ex) {
            return AdmissionValidation.rejected(List.of(error(ErrorCode.CLAIM_MALFORMED.name(),
                    "artifact." + EMBEDDED_CATALOG_MANIFEST_PATH, ex.getMessage())));
        }
        return AdmissionValidation.verified(claim, diagnostics, manifest.manifestYaml(), artifactSha);
    }

    /**
     * Archive reader: reads only the embedded catalog manifest entry of the uploaded jar bytes. ZIP failures and a
     * missing manifest are claim-admission diagnostics so operators see the same DCP error panel used for schema
     * problems.
     */
    private static ManifestSource extractEmbeddedCatalogManifest(byte[] artifactBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifactBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && EMBEDDED_CATALOG_MANIFEST_PATH.equals(entry.getName())) {
                    return ManifestSource.of(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return ManifestSource.missing("artifact." + EMBEDDED_CATALOG_MANIFEST_PATH,
                    "JAR artifact is missing " + EMBEDDED_CATALOG_MANIFEST_PATH);
        } catch (IOException ex) {
            return ManifestSource.rejected("artifact",
                    "unable to read JAR artifact catalog manifest: " + ex.getMessage());
        }
    }

    /**
     * Companion admission (HLD-in-process-component-hosting §3): a jar without a catalog manifest is a companion when it
     * carries exactly one {@code pom.properties}, read with the scan's own {@link CompanionCoordinates} reader from the
     * bytes that were hashed. Any other manifest-less jar is refused, saying it is neither a component nor a companion.
     */
    private static AdmissionValidation companionOrRejected(byte[] artifactBytes, String artifactSha, ManifestSource manifest) {
        CompanionCoordinates.Identity identity;
        try {
            identity = CompanionCoordinates.read(artifactBytes);
        } catch (IOException ex) {
            return AdmissionValidation.rejected(List.of(error(ErrorCode.CLAIM_MALFORMED.name(), "artifact",
                    "unable to read JAR artifact: " + ex.getMessage())));
        }
        java.util.Optional<String> coordinates = identity.single();
        if (coordinates.isEmpty()) {
            // One problem, one diagnostic: the missing manifest, saying why the jar is not a companion either.
            StudioDcpDiagnostic missing = manifest.diagnostics().get(0);
            return AdmissionValidation.rejected(List.of(error(missing.code(), missing.path(), missing.message()
                    + ", so it is not a component, and it is " + identity.refusal())));
        }
        return AdmissionValidation.companion(coordinates.get(), artifactSha, artifactBytes);
    }

    /**
     * Parser helper: binds the manifest's top-level {@code claim} block to the DCP Claim record.
     */
    private Claim parseClaim(String manifestYaml) throws IOException {
        JsonNode root = yamlMapper.readTree(manifestYaml);
        return yamlMapper.treeToValue(root.get("claim"), Claim.class);
    }

    /**
     * Normalizes a declared hash to {@code sha256:<lowercase hex>} for comparison with the computed one.
     */
    private static String normalizedSha(String declared) {
        String value = declared.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    /**
     * Lowercase hex SHA-256 of the uploaded bytes.
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available on this JVM", ex);
        }
    }

    /**
     * Factory helper for one admission error diagnostic.
     */
    private static StudioDcpDiagnostic error(String code, String path, String message) {
        return new StudioDcpDiagnostic(Severity.ERROR.name(), code, path, message);
    }

    /**
     * Projection helper: narrows protocol diagnostics to the fields the Studio API promises.
     */
    private static StudioDcpDiagnostic toStudioDiagnostic(Diagnostic diagnostic) {
        return new StudioDcpDiagnostic(
                diagnostic.severity() == null ? "" : diagnostic.severity().name(),
                diagnostic.code() == null ? "" : diagnostic.code().name(),
                diagnostic.fieldPath(),
                diagnostic.message());
    }

    /**
     * Factory helper: creates a permissive YAML mapper for incoming documents while leaving all
     * semantic validation to DCP's ClaimValidator.
     */
    private static ObjectMapper buildYamlMapper() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /**
     * Result object: pairs a parsed claim with its structured diagnostics, the manifest text it was read from and the
     * artifact hash Studio computed, so the caller can atomically decide whether to admit the artifact.
     *
     * @param claim        the claim, or null when rejected.
     * @param diagnostics  the admission diagnostics.
     * @param claimYaml    the exact {@code META-INF/unfurl-catalog.yaml} text read from the jar, or empty when rejected.
     * @param artifactSha  {@code sha256:<hex>} of the uploaded bytes, or empty when rejected.
     */
    record AdmissionValidation(
            Claim claim,
            List<StudioDcpDiagnostic> diagnostics,
            String claimYaml,
            String artifactSha,
            String companionCoordinates,
            byte[] companionBytes
    ) {
        /**
         * Invariant constructor: copied diagnostics prevent mutation after validation. A companion outcome carries
         * coordinates and bytes and no claim; a component outcome carries a claim and neither.
         */
        AdmissionValidation {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            claimYaml = claimYaml == null ? "" : claimYaml;
            artifactSha = artifactSha == null ? "" : artifactSha;
            companionCoordinates = companionCoordinates == null ? "" : companionCoordinates;
            companionBytes = companionBytes == null ? new byte[0] : companionBytes.clone();
        }

        /**
         * Factory for a verified claim with zero or more warning diagnostics.
         */
        static AdmissionValidation verified(Claim claim, List<StudioDcpDiagnostic> diagnostics, String claimYaml,
                                            String artifactSha) {
            return new AdmissionValidation(claim, diagnostics, claimYaml, artifactSha, "", null);
        }

        /**
         * Factory for a rejected artifact; the absent claim prevents accidental catalog updates.
         */
        static AdmissionValidation rejected(List<StudioDcpDiagnostic> diagnostics) {
            return new AdmissionValidation(null, diagnostics, "", "", "", null);
        }

        /** Factory for a companion jar: its one coordinate set, its hash, and the bytes to store. */
        static AdmissionValidation companion(String coordinates, String artifactSha, byte[] bytes) {
            return new AdmissionValidation(null, List.of(), "", artifactSha, coordinates, bytes);
        }

        /** Predicate: the upload is a companion jar, admitted to the tenant's companion store rather than the catalog. */
        boolean companion() {
            return !companionCoordinates.isBlank();
        }

        /**
         * Predicate: only claims with no error diagnostics can enter the Studio catalog.
         */
        boolean verified() {
            return claim != null && diagnostics.stream()
                    .noneMatch(diagnostic -> Severity.ERROR.name().equals(diagnostic.severity()));
        }
    }

    /**
     * Value object: carries either the manifest text read from the jar or the diagnostics explaining why it could
     * not be read.
     */
    private record ManifestSource(String manifestYaml, List<StudioDcpDiagnostic> diagnostics, boolean absent) {
        /**
         * Invariant constructor: normalizes nullable helper output to immutable values.
         */
        private ManifestSource {
            manifestYaml = manifestYaml == null ? "" : manifestYaml;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** Factory for a readable jar that has no catalog manifest: possibly a companion. */
        private static ManifestSource missing(String field, String message) {
            return new ManifestSource("", List.of(error(ErrorCode.CLAIM_MALFORMED.name(), field, message)), true);
        }

        /**
         * Factory for a manifest read from the jar.
         */
        private static ManifestSource of(String manifestYaml) {
            return new ManifestSource(manifestYaml, List.of(), false);
        }

        /**
         * Factory for a manifest that could not be read.
         */
        private static ManifestSource rejected(String path, String message) {
            return new ManifestSource("", List.of(error(ErrorCode.CLAIM_MALFORMED.name(), path, message)), false);
        }
    }
}
