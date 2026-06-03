package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.deployment.domain.ComponentShapeProfile;
import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.deployment.domain.SubstrateShapeSupport;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.deployment.plan.DeploymentResolutionException;
import com.unfurl.deployment.plan.RejectedShape;
import com.unfurl.deployment.policy.DeploymentPolicy;
import com.unfurl.deployment.policy.DeploymentRuntime;
import com.unfurl.deployment.resolver.DeploymentComponent;
import com.unfurl.deployment.resolver.DeploymentShapeResolver;
import com.unfurl.deployment.resolver.ResolverOutcome;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.matcher.StructuralMatcher;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import com.unfurl.fabric.trust.TrustClassification;
import com.unfurl.fabric.trust.TrustClassifier;
import com.unfurl.fabric.trust.TrustPolicy;
import com.unfurl.substrate.api.BindingMode;

import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class StudioDeploymentService {

    public StudioDeploymentResolveResponse resolveDeployment(StudioDeploymentResolveRequest request) {
        Catalog catalog = new CatalogScanner().scan(request.catalogPath()).catalog();
        Need need = new NeedsCodec().read(request.needsPath());
        TrustPolicy trustPolicy = request.trustPolicy() == null ? TrustPolicy.permissive() : request.trustPolicy();
        TrustClassification trust = new TrustClassifier().classify(catalog, trustPolicy);
        MatchResult match = new StructuralMatcher().match(trust.allowedEntries(), need, trust.rejectedEntries());

        Selection selection = selectCandidate(match, request);
        if (selection.invalid() != null) {
            return selection.invalid();
        }

        try {
            ResolverOutcome outcome = new DeploymentShapeResolver().resolve(
                    deploymentComponents(selection.candidate()),
                    allShapeSupport(),
                    deploymentPolicy(request.deploymentPolicy()));
            return StudioDeploymentResolveResponse.resolved(
                    selection.candidate().candidateId(),
                    selections(outcome),
                    warnings(selection.candidate()));
        } catch (DeploymentResolutionException ex) {
            return StudioDeploymentResolveResponse.invalid(
                    "DEPLOYMENT_RESOLUTION_FAILED",
                    ex.getMessage(),
                    ex.partialReport()
                            .map(report -> report.rejectedShapesWithReasons().values().stream()
                                    .flatMap(List::stream)
                                    .map(this::renderRejection)
                                    .sorted()
                                    .toList())
                            .orElse(List.of()));
        }
    }

    private Selection selectCandidate(MatchResult result, StudioDeploymentResolveRequest request) {
        if (result instanceof MatchResult.ExactMatch exact) {
            return new Selection(exact.candidate(), null);
        }
        if (result instanceof MatchResult.Ambiguous ambiguous) {
            if (request.candidateId() != null && !request.candidateId().isBlank()) {
                return ambiguous.findById(request.candidateId())
                        .map(candidate -> new Selection(candidate, null))
                        .orElseGet(() -> new Selection(null, StudioDeploymentResolveResponse.invalid(
                                "UNKNOWN_CANDIDATE_ID",
                                "unknown candidate id " + request.candidateId() + "; valid ids: " + validIds(ambiguous),
                                List.of())));
            }
            if (request.autoSelectBest()) {
                return new Selection(ambiguous.candidates().get(0), null);
            }
            return new Selection(null, StudioDeploymentResolveResponse.invalid(
                    "AMBIGUOUS_CANDIDATES",
                    "pass candidateId or autoSelectBest; valid ids: " + validIds(ambiguous),
                    List.of()));
        }
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) result;
        String details = noMatch.missing().stream()
                .map(missing -> missing.capability() + " " + missing.requestedRange())
                .collect(Collectors.joining(", "));
        return new Selection(null, StudioDeploymentResolveResponse.invalid(
                "NO_MATCH",
                details.isBlank() ? "no valid composition" : "missing " + details,
                List.of()));
    }

    private List<DeploymentComponent> deploymentComponents(CompositionCandidate candidate) {
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

    private ComponentShapeProfile inferredShapeProfile(CatalogEntry entry) {
        DeploymentShape shape = switch (entry.metadata().binding().defaultMode()) {
            case REMOTE_HTTP -> DeploymentShape.REMOTE_MICROSERVICE;
            default -> DeploymentShape.IN_PROCESS_LIBRARY;
        };
        return new ComponentShapeProfile(shape, Set.of(shape), java.util.Map.of());
    }

    private SubstrateShapeSupport allShapeSupport() {
        return new SubstrateShapeSupport(EnumSet.allOf(DeploymentShape.class));
    }

    private DeploymentPolicy deploymentPolicy(StudioDeploymentPolicyDraft draft) {
        if (draft == null) {
            return defaultDeploymentPolicy();
        }
        return new DeploymentPolicy(
                shapes(draft.preferredShapes()),
                Set.copyOf(shapes(draft.disallowedShapes())),
                draft.requireIsolationForCapabilityPatterns(),
                runtime(draft.runtime()));
    }

    private DeploymentRuntime runtime(StudioDeploymentRuntimeDraft draft) {
        StudioDeploymentRuntimeDraft effective = draft == null
                ? new StudioDeploymentRuntimeDraft(null, null, null, null, null)
                : draft;
        return new DeploymentRuntime(
                blankToNull(effective.javaVersion()),
                Boolean.TRUE.equals(effective.springBoot()),
                Boolean.TRUE.equals(effective.kubernetes()),
                Boolean.TRUE.equals(effective.serviceMesh()),
                effective.maxServices() == null
                        ? OptionalInt.empty()
                        : OptionalInt.of(effective.maxServices()));
    }

    private DeploymentPolicy defaultDeploymentPolicy() {
        return new DeploymentPolicy(
                List.of(DeploymentShape.IN_PROCESS_LIBRARY),
                Set.of(),
                List.of(),
                new DeploymentRuntime(null, true, true, true, OptionalInt.empty()));
    }

    private List<DeploymentShape> shapes(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> DeploymentShape.valueOf(value.trim()))
                .toList();
    }

    private List<StudioDeploymentSelection> selections(ResolverOutcome outcome) {
        return outcome.plan().entries().stream()
                .map(this::selection)
                .toList();
    }

    private StudioDeploymentSelection selection(BindingPlanEntry entry) {
        return new StudioDeploymentSelection(
                entry.componentId(),
                entry.artifactCoordinates(),
                entry.capability(),
                entry.deploymentShape(),
                entry.requiredSubstratePorts());
    }

    private List<String> warnings(CompositionCandidate candidate) {
        TreeSet<String> warnings = new TreeSet<>();
        candidate.warnings().forEach(warning -> warnings.add(
                warning.getClass().getSimpleName() + ": " + warning.detail()));
        return List.copyOf(warnings);
    }

    private String validIds(MatchResult.Ambiguous ambiguous) {
        return ambiguous.candidates().stream()
                .map(CompositionCandidate::candidateId)
                .collect(Collectors.joining(", "));
    }

    private String renderRejection(RejectedShape rejected) {
        return rejected.shape() + ": " + rejected.detail();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Selection(CompositionCandidate candidate, StudioDeploymentResolveResponse invalid) {
    }
}
