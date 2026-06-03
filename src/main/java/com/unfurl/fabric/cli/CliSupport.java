package com.unfurl.fabric.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.deployment.domain.ComponentShapeProfile;
import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.deployment.domain.SubstrateShapeSupport;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.DeploymentResolutionException;
import com.unfurl.deployment.plan.DeploymentResolutionReport;
import com.unfurl.deployment.policy.DeploymentPolicy;
import com.unfurl.deployment.policy.DeploymentRuntime;
import com.unfurl.deployment.resolver.DeploymentComponent;
import com.unfurl.deployment.resolver.DeploymentShapeResolver;
import com.unfurl.deployment.resolver.ResolverOutcome;
import com.unfurl.deployment.serialization.DeploymentPolicyCodec;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.compiler.ContractCompiler;
import com.unfurl.fabric.compiler.HostOwnerMeta;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.matcher.StructuralMatcher;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import com.unfurl.fabric.substrate.SubstrateProfileDeriver;
import com.unfurl.fabric.trust.TrustClassification;
import com.unfurl.fabric.trust.TrustClassifier;
import com.unfurl.fabric.trust.TrustPolicy;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

final class CliSupport {
    static final Clock CLI_COMPILE_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    static CatalogScanReport scan(CliArgs args) {
        return new CatalogScanner().scan(args.requiredPath("catalog"));
    }

    static Need loadNeed(CliArgs args) {
        return new NeedsCodec().read(args.requiredPath("needs"));
    }

    static TrustPolicy loadTrustPolicy(CliArgs args) {
        Path path = args.optionalPath("trust-policy");
        if (path == null) {
            return TrustPolicy.permissive();
        }
        try {
            return yamlMapper().readValue(path.toFile(), TrustPolicy.class);
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read trust policy " + path + ": " + ex.getMessage());
        }
    }

    static Planned planned(CliArgs args) {
        Catalog catalog = scan(args).catalog();
        Need need = loadNeed(args);
        TrustClassification trust = new TrustClassifier().classify(catalog, loadTrustPolicy(args));
        MatchResult result = new StructuralMatcher().match(trust.allowedEntries(), need, trust.rejectedEntries());
        return new Planned(catalog, need, trust, result);
    }

    static DeploymentPolicy loadDeploymentPolicy(CliArgs args) {
        Path path = args.optionalPath("deployment-policy");
        if (path == null) {
            return defaultDeploymentPolicy();
        }
        try {
            return new DeploymentPolicyCodec().parse(Files.readString(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read deployment policy " + path + ": " + ex.getMessage());
        }
    }

    static CompiledWithProfile compileCandidate(CompositionCandidate candidate, Need need, String selectionMode) {
        return compileCandidate(candidate, need, selectionMode, defaultDeploymentPolicy());
    }

    static CompiledWithProfile compileCandidate(
            CompositionCandidate candidate,
            Need need,
            String selectionMode,
            DeploymentPolicy deploymentPolicy) {
        ResolverOutcome resolved = resolveDeployment(candidate, deploymentPolicy);
        CompiledContract compiled = new ContractCompiler(CLI_COMPILE_CLOCK)
                .compile(candidate, need, new HostOwnerMeta(null, null, null));
        BindingPlan bindingPlan = resolved.plan();
        SubstrateProfile profile = new SubstrateProfileDeriver().derive(candidate, bindingPlan);
        SubstrateProfileCodec profileCodec = new SubstrateProfileCodec();
        SubstrateProfile hashedProfile = profile.withProfileHash(profileCodec.computeProfileHash(profile));
        CompiledContract withProfileHash = new CompiledContract(
                compiled.contract(),
                selectionsWithDeploymentShapes(compiled.selections(), bindingPlan),
                compiled.audit().withSelectionMode(selectionMode),
                hashedProfile.profileHash(),
                bindingPlan,
                compiled.signature());
        return new CompiledWithProfile(withProfileHash, hashedProfile, resolved.report());
    }

    static ResolverOutcome resolveDeployment(CompositionCandidate candidate, DeploymentPolicy deploymentPolicy) {
        try {
            return new DeploymentShapeResolver().resolve(
                    deploymentComponents(candidate),
                    allShapeSupport(),
                    deploymentPolicy == null ? defaultDeploymentPolicy() : deploymentPolicy);
        } catch (DeploymentResolutionException ex) {
            throw FabricCliException.runtime(renderDeploymentFailure(ex));
        }
    }

    static void writeCompiled(CompiledContract compiled, Path out) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, new CompiledContractCodec().write(compiled));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write compiled contract " + out + ": " + ex.getMessage());
        }
    }

    static Path defaultProfilePath(Path contractOut) {
        String fileName = contractOut.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path parent = contractOut.toAbsolutePath().getParent();
        Path profileName = Path.of(base + ".substrate-profile.yaml");
        return parent == null ? profileName : parent.resolve(profileName);
    }

    static void writeProfile(SubstrateProfile profile, Path out) {
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, new SubstrateProfileCodec().write(profile));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to write substrate profile " + out + ": " + ex.getMessage());
        }
    }

    static CompiledContract readCompiled(Path path) {
        try {
            return new CompiledContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read compiled contract " + path + ": " + ex.getMessage());
        }
    }

    static String candidateLine(CompositionCandidate candidate) {
        return candidate.candidateId() + " score=" + candidate.score().finalScore()
                + " entries=" + candidate.entries().stream()
                .map(e -> e.artifact().coordinates())
                .collect(Collectors.joining(","));
    }

    static String entryCapabilities(CatalogEntry entry) {
        return entry.claimDescriptor().claim().offers().stream()
                .map(o -> o.capability() + ":" + o.version())
                .collect(Collectors.joining(", "));
    }

    private static ObjectMapper yamlMapper() {
        YAMLFactory yamlFactory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(yamlFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    private static List<DeploymentComponent> deploymentComponents(CompositionCandidate candidate) {
        return candidate.entries().stream()
                .sorted(CatalogEntry.CANONICAL_ORDER)
                .map(entry -> new DeploymentComponent(
                        entry.artifact().coordinates(),
                        entry.artifact().coordinates(),
                        entry.artifact().sha256(),
                        entry.claimDescriptor().claim().offers().stream()
                                .map(Offer::capability)
                                .sorted()
                                .toList(),
                        entry.optionalComponentShapeProfile().orElseGet(() -> inferredShapeProfile(entry))))
                .toList();
    }

    private static ComponentShapeProfile inferredShapeProfile(CatalogEntry entry) {
        DeploymentShape shape = switch (entry.metadata().binding().defaultMode()) {
            case REMOTE_HTTP -> DeploymentShape.REMOTE_MICROSERVICE;
            default -> DeploymentShape.IN_PROCESS_LIBRARY;
        };
        return new ComponentShapeProfile(shape, java.util.Set.of(shape), java.util.Map.of());
    }

    private static SubstrateShapeSupport allShapeSupport() {
        return new SubstrateShapeSupport(java.util.EnumSet.allOf(DeploymentShape.class));
    }

    private static DeploymentPolicy defaultDeploymentPolicy() {
        return new DeploymentPolicy(
                List.of(DeploymentShape.IN_PROCESS_LIBRARY),
                java.util.Set.of(),
                List.of(),
                new DeploymentRuntime(null, true, true, true, OptionalInt.empty()));
    }

    private static List<com.unfurl.fabric.compiler.SelectionRecord> selectionsWithDeploymentShapes(
            List<com.unfurl.fabric.compiler.SelectionRecord> selections,
            BindingPlan bindingPlan) {
        java.util.Map<String, DeploymentShape> shapesByCoordinates = bindingPlan.entries().stream()
                .collect(Collectors.toMap(
                        com.unfurl.deployment.plan.BindingPlanEntry::artifactCoordinates,
                        com.unfurl.deployment.plan.BindingPlanEntry::deploymentShape,
                        (left, right) -> left,
                        java.util.TreeMap::new));
        return selections.stream()
                .map(selection -> new com.unfurl.fabric.compiler.SelectionRecord(
                        selection.artifact(),
                        selection.claimHash(),
                        selection.bindingMode(),
                        selection.chosenInterfaceKind(),
                        shapesByCoordinates.get(selection.artifact().coordinates())))
                .toList();
    }

    private static String renderDeploymentFailure(DeploymentResolutionException ex) {
        StringBuilder message = new StringBuilder("deployment shape resolution failed: ")
                .append(ex.getMessage());
        ex.partialReport().ifPresent(report -> appendReport(message, report));
        return message.toString();
    }

    private static void appendReport(StringBuilder out, DeploymentResolutionReport report) {
        if (!report.rejectedShapesWithReasons().isEmpty()) {
            out.append(System.lineSeparator()).append("rejectedShapes:");
            report.rejectedShapesWithReasons().forEach((component, rejections) -> {
                out.append(System.lineSeparator()).append("- ").append(component);
                rejections.forEach(rejection -> out.append(System.lineSeparator())
                        .append("  ")
                        .append(rejection.shape())
                        .append(": ")
                        .append(rejection.detail()));
            });
        }
    }

    record Planned(Catalog catalog, Need need, TrustClassification trust, MatchResult result) {
    }

    record CompiledWithProfile(
            CompiledContract contract,
            SubstrateProfile profile,
            DeploymentResolutionReport deploymentReport) {
    }

    private CliSupport() {
    }
}
