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
import com.unfurl.dcp.validation.Diagnostic;
import com.unfurl.dcp.validation.ErrorCode;
import com.unfurl.dcp.validation.Severity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adapter: turns Studio-uploaded YAML into DCP claim-validation results. It accepts
 * either a pure DCP Claim document or Fabric's catalog-manifest envelope with a
 * top-level {@code claim} block, then delegates all schema decisions to
 * {@link ClaimValidator} so Fabric admission cannot drift from the protocol library.
 */
final class StudioClaimAdmissionValidator {
    private static final String EMBEDDED_CATALOG_MANIFEST_PATH = "META-INF/unfurl-catalog.yaml";

    private final ObjectMapper yamlMapper;
    private final ClaimValidator claimValidator;

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
     * Strategy method: parse the artifact's claim YAML and return a verified/rejected result.
     * Missing or malformed YAML or JAR manifests are represented as DCP-shaped diagnostics so
     * the Studio UI can render every admission failure through one diagnostic surface.
     */
    AdmissionValidation validate(StudioComponentArtifactDraft artifact) {
        ClaimYamlSource claimYamlSource = resolveClaimYaml(artifact);
        if (!claimYamlSource.diagnostics().isEmpty()) {
            return AdmissionValidation.rejected(claimYamlSource.diagnostics());
        }
        if (claimYamlSource.claimYaml().isBlank()) {
            return AdmissionValidation.rejected(List.of(new StudioDcpDiagnostic(
                    Severity.ERROR.name(),
                    ErrorCode.CLAIM_MALFORMED.name(),
                    "claim",
                    "DCP claim YAML is required for catalog admission")));
        }
        try {
            Claim claim = parseClaim(claimYamlSource.claimYaml());
            List<StudioDcpDiagnostic> diagnostics = claimValidator.validate(claim).diagnostics().stream()
                    .map(StudioClaimAdmissionValidator::toStudioDiagnostic)
                    .toList();
            boolean hasErrors = diagnostics.stream()
                    .anyMatch(diagnostic -> Severity.ERROR.name().equals(diagnostic.severity()));
            return hasErrors
                    ? AdmissionValidation.rejected(diagnostics)
                    : AdmissionValidation.verified(claim, diagnostics, claimYamlSource.claimYaml());
        } catch (IOException | RuntimeException ex) {
            return AdmissionValidation.rejected(List.of(new StudioDcpDiagnostic(
                    Severity.ERROR.name(),
                    ErrorCode.CLAIM_MALFORMED.name(),
                    "claim",
                    "unable to parse DCP claim YAML: " + ex.getMessage())));
        }
    }

    /**
     * Adapter helper: chooses the claim source for an uploaded draft. Explicit YAML wins for
     * compatibility, while JAR drafts can supply archive bytes so Studio can extract the DCP
     * catalog manifest without executing any artifact code.
     */
    private ClaimYamlSource resolveClaimYaml(StudioComponentArtifactDraft artifact) {
        if (artifact == null) {
            return ClaimYamlSource.empty();
        }
        if (!artifact.claimYaml().isBlank()) {
            return ClaimYamlSource.fromYaml(artifact.claimYaml());
        }
        if (artifact.fileName().toLowerCase().endsWith(".jar") && !artifact.artifactBase64().isBlank()) {
            return extractEmbeddedCatalogManifest(artifact.artifactBase64());
        }
        return ClaimYamlSource.empty();
    }

    /**
     * Archive reader: decodes a Studio-uploaded JAR and reads only the embedded catalog
     * manifest entry. It treats base64 and ZIP failures as claim-admission diagnostics so
     * operators see the same DCP error panel used for schema problems.
     */
    private ClaimYamlSource extractEmbeddedCatalogManifest(String artifactBase64) {
        try {
            byte[] artifactBytes = Base64.getDecoder().decode(artifactBase64);
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifactBytes))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && EMBEDDED_CATALOG_MANIFEST_PATH.equals(entry.getName())) {
                        return ClaimYamlSource.fromYaml(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            return ClaimYamlSource.rejected("artifact." + EMBEDDED_CATALOG_MANIFEST_PATH,
                    "JAR artifact is missing " + EMBEDDED_CATALOG_MANIFEST_PATH);
        } catch (IllegalArgumentException ex) {
            return ClaimYamlSource.rejected("artifactBase64",
                    "JAR artifactBase64 is not valid base64: " + ex.getMessage());
        } catch (IOException ex) {
            return ClaimYamlSource.rejected("artifact",
                    "unable to read JAR artifact catalog manifest: " + ex.getMessage());
        }
    }

    /**
     * Parser helper: supports both direct claim YAML and Fabric catalog manifests by selecting
     * the top-level {@code claim} node when present before binding to the DCP Claim record.
     */
    private Claim parseClaim(String claimYaml) throws IOException {
        JsonNode root = yamlMapper.readTree(claimYaml);
        JsonNode claimNode = root.has("claim") ? root.get("claim") : root;
        return yamlMapper.treeToValue(claimNode, Claim.class);
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
     * Result object: pairs a parsed claim with its structured diagnostics so the caller can
     * atomically decide whether to admit the artifact and what to show in the UI.
     */
    record AdmissionValidation(
            Claim claim,
            List<StudioDcpDiagnostic> diagnostics,
            String claimYaml
    ) {
        /**
         * Invariant constructor: copied diagnostics prevent mutation after validation.
         */
        AdmissionValidation {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            claimYaml = claimYaml == null ? "" : claimYaml;
        }

        /**
         * Factory for a verified claim with zero or more warning diagnostics.
         */
        static AdmissionValidation verified(Claim claim, List<StudioDcpDiagnostic> diagnostics, String claimYaml) {
            return new AdmissionValidation(claim, diagnostics, claimYaml);
        }

        /**
         * Factory for a rejected artifact; the absent claim prevents accidental catalog updates.
         */
        static AdmissionValidation rejected(List<StudioDcpDiagnostic> diagnostics) {
            return new AdmissionValidation(null, diagnostics, "");
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
     * Value object: carries either resolved claim YAML or admission diagnostics from the
     * source-selection step before semantic DCP validation begins.
     */
    private record ClaimYamlSource(
            String claimYaml,
            List<StudioDcpDiagnostic> diagnostics
    ) {
        /**
         * Invariant constructor: normalizes nullable helper output to immutable values.
         */
        private ClaimYamlSource {
            claimYaml = claimYaml == null ? "" : claimYaml;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /**
         * Factory for drafts that provide no usable claim source.
         */
        private static ClaimYamlSource empty() {
            return new ClaimYamlSource("", List.of());
        }

        /**
         * Factory for drafts whose claim material has been resolved to YAML text.
         */
        private static ClaimYamlSource fromYaml(String claimYaml) {
            return new ClaimYamlSource(claimYaml, List.of());
        }

        /**
         * Factory for claim-source failures that should block catalog admission.
         */
        private static ClaimYamlSource rejected(String path, String message) {
            return new ClaimYamlSource("", List.of(new StudioDcpDiagnostic(
                    Severity.ERROR.name(),
                    ErrorCode.CLAIM_MALFORMED.name(),
                    path,
                    message)));
        }
    }
}
