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

import java.io.IOException;
import java.util.List;

/**
 * Adapter: turns Studio-uploaded YAML into DCP claim-validation results. It accepts
 * either a pure DCP Claim document or Fabric's catalog-manifest envelope with a
 * top-level {@code claim} block, then delegates all schema decisions to
 * {@link ClaimValidator} so Fabric admission cannot drift from the protocol library.
 */
final class StudioClaimAdmissionValidator {
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
     * Missing or malformed YAML is represented as DCP-shaped diagnostics so the Studio UI can
     * render every admission failure through one diagnostic surface.
     */
    AdmissionValidation validate(StudioComponentArtifactDraft artifact) {
        if (artifact == null || artifact.claimYaml().isBlank()) {
            return AdmissionValidation.rejected(List.of(new StudioDcpDiagnostic(
                    Severity.ERROR.name(),
                    ErrorCode.CLAIM_MALFORMED.name(),
                    "claim",
                    "DCP claim YAML is required for catalog admission")));
        }
        try {
            Claim claim = parseClaim(artifact.claimYaml());
            List<StudioDcpDiagnostic> diagnostics = claimValidator.validate(claim).diagnostics().stream()
                    .map(StudioClaimAdmissionValidator::toStudioDiagnostic)
                    .toList();
            boolean hasErrors = diagnostics.stream()
                    .anyMatch(diagnostic -> Severity.ERROR.name().equals(diagnostic.severity()));
            return hasErrors
                    ? AdmissionValidation.rejected(diagnostics)
                    : AdmissionValidation.verified(claim, diagnostics);
        } catch (IOException | RuntimeException ex) {
            return AdmissionValidation.rejected(List.of(new StudioDcpDiagnostic(
                    Severity.ERROR.name(),
                    ErrorCode.CLAIM_MALFORMED.name(),
                    "claim",
                    "unable to parse DCP claim YAML: " + ex.getMessage())));
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
            List<StudioDcpDiagnostic> diagnostics
    ) {
        /**
         * Invariant constructor: copied diagnostics prevent mutation after validation.
         */
        AdmissionValidation {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /**
         * Factory for a verified claim with zero or more warning diagnostics.
         */
        static AdmissionValidation verified(Claim claim, List<StudioDcpDiagnostic> diagnostics) {
            return new AdmissionValidation(claim, diagnostics);
        }

        /**
         * Factory for a rejected artifact; the absent claim prevents accidental catalog updates.
         */
        static AdmissionValidation rejected(List<StudioDcpDiagnostic> diagnostics) {
            return new AdmissionValidation(null, diagnostics);
        }

        /**
         * Predicate: only claims with no error diagnostics can enter the Studio catalog.
         */
        boolean verified() {
            return claim != null && diagnostics.stream()
                    .noneMatch(diagnostic -> Severity.ERROR.name().equals(diagnostic.severity()));
        }
    }
}
