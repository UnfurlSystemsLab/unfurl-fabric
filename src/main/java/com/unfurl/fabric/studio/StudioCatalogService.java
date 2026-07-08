package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.ComponentKind;
import com.unfurl.dcp.claim.ConsumerAccess;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OfferInterface;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.dcp.projection.DcpProjection;
import com.unfurl.dcp.projection.DcpProjectionEdge;
import com.unfurl.dcp.projection.DcpProjectionNode;
import com.unfurl.dcp.projection.DcpProjectionProjector;
import com.unfurl.dcp.projection.DcpProjectionRequest;
import com.unfurl.deployment.domain.ComponentShapeProfile;
import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.artifact.ArtifactDescriptor;
import com.unfurl.fabric.catalog.BindingDescriptor;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogManifestCodec;
import com.unfurl.fabric.catalog.CatalogMetadata;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.catalog.ClaimDescriptor;
import com.unfurl.fabric.catalog.Lifecycle;
import com.unfurl.fabric.catalog.ParsedManifest;
import com.unfurl.fabric.compile.ContractCompiler;
import com.unfurl.fabric.compile.HostOwnerMeta;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.matcher.CandidateValidity;
import com.unfurl.fabric.matcher.CandidateValidator;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.Conflict;
import com.unfurl.fabric.matcher.DependencyBinding;
import com.unfurl.fabric.matcher.PlanningWarning;
import com.unfurl.fabric.matcher.Scorer;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import com.unfurl.fabric.signing.FabricContractSigner;
import com.unfurl.fabric.signing.FabricSigningException;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.fabric.signing.SigningKeyLoader;
import com.unfurl.fabric.substrate.SubstrateProfileDeriver;
import com.unfurl.fabric.workflow.WorkflowAnalysisException;
import com.unfurl.fabric.workflow.WorkflowAnalyzer;
import com.unfurl.deployment.resolver.ResolverOutcome;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Facade: owns Fabric Studio's tenant-scoped catalog, assembly, layout, draft-session, diagnostic,
 * and projection state.
 *
 * <p>Pattern: application service/facade over DCP catalog parsing, Studio read models, governed
 * intent handling, and optional Foundry authoring delegation. Inputs are API DTOs from
 * `StudioTenantHandler`/`StudioAuthoringHandler`; outputs are immutable Studio response records.
 * Invariants: tenant ids are normalized at route boundaries, catalog entries stay content-pinned,
 * and UI layout state never becomes contract validity.
 */
public final class StudioCatalogService {
    private final Map<String, List<StudioVisualCatalogEntry>> entriesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioLayoutState>> layoutsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioDraftSession>> sessionsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssetContent>> claimBundlesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssetContent>> diagnosticArtifactsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssetContent>> exportArtifactsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CatalogEntry>> catalogEntriesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Need>> needsByTenant = new ConcurrentHashMap<>();
    private final StudioStateStore store;
    private final Path assetRoot;
    private final StudioSessionEventBus eventBus;
    private final StudioPackageVisualAssets packageVisualAssets = new StudioPackageVisualAssets();
    private final StudioClaimAdmissionValidator claimAdmissionValidator = new StudioClaimAdmissionValidator();
    private final ObjectMapper jsonMapper = StudioJson.mapper();
    private final StudioDeploymentService deploymentService = new StudioDeploymentService();

    /**
     * Optional DCP authoring proposer. When Fabric is configured with a DCP transport to
     * {@code unfurl-foundry}'s {@code agent.run} endpoint, {@code converseAuthoring} routes through
     * it; otherwise it uses the deterministic in-Fabric bridge. Fabric depends only on the neutral
     * composition types, never on foundry.
     */
    private ContractInvocable authoringInvocable;

    public StudioCatalogService() {
        this(null, defaultAssetRoot());
    }

    public StudioCatalogService(StudioStateStore store) {
        this(store, defaultAssetRoot());
    }

    public StudioCatalogService(StudioStateStore store, Path assetRoot) {
        this(store, assetRoot, new InMemoryStudioSessionEventBus());
    }

    public StudioCatalogService(StudioStateStore store, Path assetRoot, StudioSessionEventBus eventBus) {
        this.store = store;
        this.assetRoot = assetRoot == null ? null : assetRoot.toAbsolutePath().normalize();
        this.eventBus = eventBus == null ? new InMemoryStudioSessionEventBus() : eventBus;
        if (store != null) {
            StudioStateStore.State state = store.load();
            entriesByTenant.putAll(state.entriesByTenant());
            state.assembliesByTenant().forEach((tenant, assemblies) ->
                    assembliesByTenant.put(tenant, new ConcurrentHashMap<>(assemblies)));
            state.layoutsByTenant().forEach((tenant, layouts) ->
                    layoutsByTenant.put(tenant, new ConcurrentHashMap<>(layouts)));
            state.sessionsByTenant().forEach((tenant, sessions) ->
                    sessionsByTenant.put(tenant, new ConcurrentHashMap<>(sessions)));
        }
    }

    public StudioCatalogVisualsResponse listCatalogVisuals(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        StudioCatalogVisualsResponse snapshot = response(entries);
        return new StudioCatalogVisualsResponse(
                snapshot.catalogHash(),
                snapshot.entries(),
                List.of(diagnosticArtifact(tenant, "catalog-snapshot", "catalog.json", snapshot)));
    }

    /**
     * Snapshot factory: captures the tenant catalog read model as portable JSON
     * while preserving the catalog hash Fabric uses for draft grounding.
     */
    public StudioCatalogSnapshot saveCatalogSnapshot(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        StudioCatalogVisualsResponse catalog = response(entries);
        StudioCatalogSnapshot snapshot = new StudioCatalogSnapshot(
                tenant,
                catalog.catalogHash(),
                catalog.entries(),
                List.of());
        return new StudioCatalogSnapshot(
                snapshot.tenantId(),
                snapshot.catalogHash(),
                snapshot.entries(),
                List.of(diagnosticArtifact(tenant, "catalog-snapshot", "catalog-snapshot.json", snapshot)));
    }

    /**
     * Snapshot loader: replaces the route tenant's catalog from a portable
     * snapshot. The route tenant is authoritative so imported JSON cannot escape
     * the tenant boundary.
     */
    public StudioCatalogVisualsResponse loadCatalogSnapshot(String tenantId, StudioCatalogSnapshot snapshot) {
        String tenant = normalizeTenant(tenantId);
        if (snapshot == null) {
            throw new IllegalArgumentException("catalog snapshot is required");
        }
        List<StudioVisualCatalogEntry> entries = snapshot.entries().stream()
                .sorted(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId))
                .toList();
        entriesByTenant.put(tenant, List.copyOf(entries));
        persist();
        StudioCatalogVisualsResponse catalog = response(entries);
        return new StudioCatalogVisualsResponse(
                catalog.catalogHash(),
                catalog.entries(),
                List.of(diagnosticArtifact(tenant, "catalog-snapshot-load", "catalog-snapshot-load.json", catalog)));
    }

    public StudioCatalogAdmissionResponse admit(String tenantId, StudioCatalogAdmissionRequest request) {
        String tenant = normalizeTenant(tenantId);
        StudioCatalogAdmissionRequest safeRequest = request == null
                ? new StudioCatalogAdmissionRequest("assembly-demo", List.of())
                : request;
        List<StudioVisualCatalogEntry> entries = new ArrayList<>(entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries));
        List<StudioClaimVerificationResult> results = new ArrayList<>();
        List<ResolvedClaimBundleEntry> resolvedClaims = new ArrayList<>();

        for (StudioComponentArtifactDraft artifact : safeRequest.artifacts()) {
            if (artifact.fileName() == null || artifact.fileName().isBlank()) {
                results.add(new StudioClaimVerificationResult("", "REJECTED", "", "", List.of("fileName is required"),
                        List.of(new StudioDcpDiagnostic("ERROR", "CLAIM_MALFORMED", "fileName", "fileName is required"))));
                continue;
            }
            if (!artifact.fileName().endsWith(".jar") && !artifact.fileName().endsWith(".yaml") && !artifact.fileName().endsWith(".yml")) {
                results.add(new StudioClaimVerificationResult(artifact.fileName(), "REJECTED", "", "",
                        List.of("unsupported artifact type"),
                        List.of(new StudioDcpDiagnostic(
                                "ERROR",
                                "CLAIM_MALFORMED",
                                "fileName",
                                "unsupported artifact type"))));
                continue;
            }
            StudioClaimAdmissionValidator.AdmissionValidation validation = claimAdmissionValidator.validate(artifact);
            if (!validation.verified()) {
                results.add(new StudioClaimVerificationResult(
                        artifact.fileName(),
                        "REJECTED",
                        "",
                        "",
                        diagnosticMessages(validation.diagnostics()),
                        validation.diagnostics()));
                continue;
            }
            String entryId = "uploaded:" + artifact.fileName().replace('\\', '/');
            String claimHash = "sha256:" + new com.unfurl.fabric.catalog.CatalogManifestCodec()
                    .computeClaimHash(validation.claim());
            String artifactSha = artifactSha(artifact, validation);
            StudioVisualCatalogEntry entry = admittedVisualEntry(entryId, validation.claim(), claimHash, artifactSha);
            entries.removeIf(existing -> existing.catalogEntryId().equals(entryId));
            entries.add(entry);
            catalogEntriesByTenant
                    .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                    .put(entryId, admittedCatalogEntry(entryId, validation, artifactSha));
            resolvedClaims.add(new ResolvedClaimBundleEntry(
                    artifact.fileName(),
                    entryId,
                    claimHash,
                    validation.claimYaml()));
            results.add(new StudioClaimVerificationResult(
                    artifact.fileName(),
                    "VERIFIED",
                    entryId,
                    claimHash,
                    diagnosticMessages(validation.diagnostics()),
                    validation.diagnostics()));
        }

        entries.sort(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId));
        entriesByTenant.put(tenant, List.copyOf(entries));
        persist();
        boolean allVerified = !results.isEmpty()
                && results.stream().allMatch(result -> "VERIFIED".equals(result.status()));
        StudioExportArtifact claimBundleArtifact = claimBundleArtifact(tenant, safeRequest.assemblyId(), results, resolvedClaims);
        StudioCatalogAdmissionResponse response = new StudioCatalogAdmissionResponse(
                tenant,
                safeRequest.assemblyId(),
                allVerified ? "VERIFIED" : "REJECTED",
                results,
                response(entries),
                claimBundleArtifact);
        return new StudioCatalogAdmissionResponse(
                response.tenantId(),
                response.assemblyId(),
                response.status(),
                response.results(),
                response.catalog(),
                response.claimBundleArtifact(),
                List.of(diagnosticArtifact(tenant, "catalog-admission", "catalog-admission.json", response)));
    }

    /**
     * Command handler: removes one catalog entry from the tenant-scoped Studio catalog
     * and returns the updated catalog snapshot. This is a tenant catalog curation
     * operation; draft sessions that already referenced the entry are left for the
     * normal intent/catalog validation path to reject or repair.
     */
    public StudioCatalogRemovalResponse removeCatalogEntry(String tenantId, String catalogEntryId) {
        String tenant = normalizeTenant(tenantId);
        if (catalogEntryId == null || catalogEntryId.isBlank()) {
            throw new IllegalArgumentException("catalogEntryId is required");
        }
        List<StudioVisualCatalogEntry> existing = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        List<StudioVisualCatalogEntry> entries = existing.stream()
                .filter(entry -> !catalogEntryId.equals(entry.catalogEntryId()))
                .sorted(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId))
                .toList();
        if (entries.size() == existing.size()) {
            throw new IllegalArgumentException("catalog entry not found: " + catalogEntryId);
        }
        entriesByTenant.put(tenant, List.copyOf(entries));
        persist();
        StudioCatalogVisualsResponse catalog = response(entries);
        StudioCatalogRemovalResponse response = new StudioCatalogRemovalResponse(
                tenant,
                catalogEntryId,
                "REMOVED",
                catalog,
                List.of());
        return new StudioCatalogRemovalResponse(
                response.tenantId(),
                response.catalogEntryId(),
                response.status(),
                response.catalog(),
                List.of(diagnosticArtifact(tenant, "catalog-removal", "catalog-removal.json", response)));
    }

    /**
     * Bundle factory: packages resolved verified claims from a multi-file admission as a
     * hash-pinned ZIP download. Claims remain separate files so Fabric preserves DCP claim
     * boundaries while still giving operators one portable catalog-admission artifact.
     */
    private StudioExportArtifact claimBundleArtifact(
            String tenantId,
            String assemblyId,
            List<StudioClaimVerificationResult> results,
            List<ResolvedClaimBundleEntry> resolvedClaims
    ) {
        if (resolvedClaims == null || resolvedClaims.isEmpty()) {
            return null;
        }
        String admissionId = "adm-" + UUID.randomUUID();
        byte[] bytes = claimBundleBytes(tenantId, assemblyId, admissionId, results, resolvedClaims);
        String sha = sha256(bytes);
        StudioAssetContent content = new StudioAssetContent(bytes, "application/zip", sha);
        claimBundlesByTenant
                .computeIfAbsent(normalizeTenant(tenantId), ignored -> new ConcurrentHashMap<>())
                .put(admissionId, content);
        return new StudioExportArtifact(
                admissionId,
                "application/zip",
                sha,
                "/studio/tenants/" + normalizeTenant(tenantId) + "/catalog/admissions/"
                        + admissionId + "/claims.zip?sha256=" + sha);
    }

    /**
     * Archive writer: emits a deterministic internal bundle shape for an admission result,
     * including per-claim YAML, a YAML manifest, and JSON diagnostics for operator review.
     */
    private byte[] claimBundleBytes(
            String tenantId,
            String assemblyId,
            String admissionId,
            List<StudioClaimVerificationResult> results,
            List<ResolvedClaimBundleEntry> resolvedClaims
    ) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                int index = 1;
                List<String> claimPaths = new ArrayList<>();
                for (ResolvedClaimBundleEntry resolvedClaim : resolvedClaims) {
                    String path = "claims/%02d-%s.claim.yaml".formatted(index, slug(resolvedClaim.fileName()));
                    claimPaths.add(path);
                    zip.putNextEntry(new ZipEntry(path));
                    zip.write(resolvedClaim.claimYaml().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    index++;
                }
                zip.putNextEntry(new ZipEntry("admission-manifest.yaml"));
                zip.write(admissionManifestYaml(tenantId, assemblyId, admissionId, resolvedClaims, claimPaths)
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("diagnostics.json"));
                zip.write(jsonMapper.writeValueAsBytes(Map.of(
                        "tenantId", normalizeTenant(tenantId),
                        "assemblyId", assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId,
                        "admissionId", admissionId,
                        "results", results == null ? List.of() : results)));
                zip.closeEntry();
            }
            return buffer.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("unable to create catalog admission claim bundle", ex);
        }
    }

    /**
     * Manifest writer: records the claim bundle index in simple YAML so shell users can
     * inspect the ZIP without needing a Studio client library.
     */
    private String admissionManifestYaml(
            String tenantId,
            String assemblyId,
            String admissionId,
            List<ResolvedClaimBundleEntry> resolvedClaims,
            List<String> claimPaths
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("tenantId: ").append(normalizeTenant(tenantId)).append('\n');
        builder.append("assemblyId: ").append(assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId).append('\n');
        builder.append("admissionId: ").append(admissionId).append('\n');
        builder.append("claims:\n");
        for (int i = 0; i < resolvedClaims.size(); i++) {
            ResolvedClaimBundleEntry claim = resolvedClaims.get(i);
            builder.append("  - fileName: ").append(yamlQuote(claim.fileName())).append('\n');
            builder.append("    catalogEntryId: ").append(yamlQuote(claim.catalogEntryId())).append('\n');
            builder.append("    claimHash: ").append(yamlQuote(claim.claimHash())).append('\n');
            builder.append("    path: ").append(yamlQuote(claimPaths.get(i))).append('\n');
        }
        return builder.toString();
    }

    /**
     * Provenance helper: computes the artifact hash Fabric should use for a Studio
     * admission. JAR uploads are hashed by bytes; pure claim YAML uploads are hashed by the
     * admitted YAML so claim-only development flows still have deterministic provenance.
     */
    private String artifactSha(
            StudioComponentArtifactDraft artifact,
            StudioClaimAdmissionValidator.AdmissionValidation validation
    ) {
        if (artifact.sha256() != null && !artifact.sha256().isBlank()) {
            return artifact.sha256();
        }
        if (!artifact.artifactBase64().isBlank()) {
            try {
                return sha256(Base64.getDecoder().decode(artifact.artifactBase64()));
            } catch (IllegalArgumentException ignored) {
                // Explicit claim YAML is the admission source in this case; hash that below.
            }
        }
        if (!validation.claimYaml().isBlank()) {
            return sha256(validation.claimYaml().getBytes(StandardCharsets.UTF_8));
        }
        return sha256("artifact:" + artifact.fileName());
    }

    /**
     * Provenance helper: converts Studio/API hash pins into the bare lowercase SHA-256
     * form required by compiled contract selection records.
     */
    private String artifactDescriptorSha(String sha256) {
        String normalized = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        if (normalized.matches("[0-9a-f]{64}")) {
            return normalized;
        }
        return sha256(normalized).substring("sha256:".length());
    }

    /**
     * Adapter: converts an admitted Studio upload back into the runtime catalog entry
     * shape used by matcher/compile/deployment resolution.
     *
     * <p>Pattern: parser adapter with a strict manifest path and a DCP-only fallback. JAR
     * manifests keep authored artifact, lifecycle, binding, and component-shape metadata;
     * pure claim YAML remains usable for local Studio authoring with explicit
     * in-process-only binding semantics.
     */
    private CatalogEntry admittedCatalogEntry(
            String entryId,
            StudioClaimAdmissionValidator.AdmissionValidation validation,
            String artifactSha
    ) {
        CatalogManifestCodec codec = new CatalogManifestCodec();
        try {
            ParsedManifest parsed = codec.parse(validation.claimYaml().getBytes(StandardCharsets.UTF_8));
            ArtifactDescriptor artifact = new ArtifactDescriptor(
                    parsed.authoredArtifact().coordinates(),
                    parsed.authoredArtifact().packaging(),
                    parsed.authoredArtifact().source(),
                    artifactDescriptorSha(artifactSha),
                    parsed.authoredArtifact().signature());
            return new CatalogEntry(
                    artifact,
                    new ClaimDescriptor(parsed.claim(), codec.computeClaimHash(parsed.claim())),
                    new CatalogMetadata(parsed.lifecycle(), parsed.binding()),
                    parsed.componentShapeProfile(),
                    null);
        } catch (RuntimeException ignored) {
            Claim claim = validation.claim();
            ArtifactDescriptor artifact = new ArtifactDescriptor(
                    fallbackArtifactCoordinates(entryId, claim),
                    "studio-claim",
                    "studio-admission",
                    artifactDescriptorSha(artifactSha),
                    null);
            return new CatalogEntry(
                    artifact,
                    new ClaimDescriptor(claim, codec.computeClaimHash(claim)),
                    new CatalogMetadata(Lifecycle.active(), BindingDescriptor.inProcessOnly()),
                    null,
                    null);
        }
    }

    /**
     * Naming helper: chooses stable artifact coordinates for direct claim uploads that do
     * not carry a catalog artifact block.
     */
    private String fallbackArtifactCoordinates(String entryId, Claim claim) {
        if (claim != null && claim.identity() != null && claim.identity().uri() != null) {
            return claim.identity().uri().toString();
        }
        return entryId == null || entryId.isBlank() ? "studio:uploaded-claim:1.0.0" : entryId;
    }

    /**
     * Projector: turns a verified uploaded DCP claim into the Studio visual-catalog shape.
     * The catalog entry id stays upload-scoped, but ports and dynamic metadata come directly
     * from the validated claim so Studio reflects the actual DCP surface.
     */
    private StudioVisualCatalogEntry admittedVisualEntry(String entryId, Claim claim, String claimHash, String artifactSha) {
        List<String> capabilities = claim.offers() == null
                ? List.of()
                : claim.offers().stream().map(Offer::capability).toList();
        List<String> requiredCapabilities = requiredCapabilitiesFromClaim(claim);
        String category = claim.identity() == null || claim.identity().kind() == null
                ? "COMPONENT"
                : claim.identity().kind().toString();
        List<String> rawNeeds = claim.dependencies() == null || claim.dependencies().needs() == null
                ? List.of()
                : List.copyOf(claim.dependencies().needs());
        return new StudioVisualCatalogEntry(
                entryId,
                claimHash,
                artifactSha,
                visual(category, "CUBE", capabilities, requiredCapabilities),
                dynamicComposition("COMPONENT", List.of(), rawNeeds),
                Map.of("visualManifestHash", sha256("visual:" + entryId), "assets", List.of()),
                List.of());
    }

    /**
     * Projection helper: keeps the legacy warnings list populated from structured diagnostics
     * while newer clients consume the richer diagnostics field.
     */
    private static List<String> diagnosticMessages(List<StudioDcpDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return List.of();
        }
        return diagnostics.stream()
                .map(diagnostic -> {
                    String path = diagnostic.path() == null || diagnostic.path().isBlank()
                            ? "claim"
                            : diagnostic.path();
                    return path + ": " + diagnostic.message();
                })
                .toList();
    }

    /**
     * Strategy: derives a governed DCP needs YAML seed for a tenant assembly.
     *
     * <p>The extractor accepts both the legacy file-name-only Studio request and richer inline
     * source files. It first derives known Flow/Foundry orchestration capabilities from source
     * names, analyzes inline Flow workflow YAML for node-level `uses` capabilities, and only emits
     * the historical application slug starter need when no DCP capability can be inferred.
     *
     * @param tenantId   route tenant id.
     * @param assemblyId route assembly id.
     * @param request    extraction hints and optional inline source files.
     * @return a Studio response carrying the needs YAML and a hash-pinned diagnostic artifact.
     */
    public StudioNeedsExtractionResponse extractNeeds(
            String tenantId,
            String assemblyId,
            StudioNeedsExtractionRequest request
    ) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        StudioNeedsExtractionRequest safeRequest = request == null
                ? new StudioNeedsExtractionRequest("target-application", List.of(), "")
                : request;
        String needsId = assembly + "-extracted-needs";
        NeedsExtractionDraft extracted = deriveNeeds(safeRequest);
        Need need = new Need(
                extracted.requiredCapabilities(),
                List.of(),
                List.of(),
                Set.of(),
                null,
                Map.of());
        needsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(needsId, need);
        String yaml = new NeedsCodec().writeToString(need);
        StudioNeedsExtractionResponse response = new StudioNeedsExtractionResponse(
                tenant,
                assembly,
                needsId,
                safeRequest.targetApplicationName(),
                yaml,
                safeRequest.defaultDeploymentTarget(),
                extracted.warnings());
        return new StudioNeedsExtractionResponse(
                response.tenantId(),
                response.assemblyId(),
                response.needsId(),
                response.targetApplicationName(),
                response.suggestedNeedsYaml(),
                response.defaultDeploymentTarget(),
                response.warnings(),
                List.of(diagnosticArtifact(tenant, "needs-extraction", "needs-extraction.json", response)));
    }

    /**
     * Strategy helper: converts source names/content into required DCP capability requirements.
     *
     * @param request normalized Studio needs extraction request.
     * @return extracted requirements plus user-visible warnings about inference limits.
     */
    private NeedsExtractionDraft deriveNeeds(StudioNeedsExtractionRequest request) {
        Map<String, CapabilityRequirement> required = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        request.fileNames().forEach(fileName -> inferCapabilitiesFromSourceName(fileName, required));
        if (!request.fileNames().isEmpty() && request.files().isEmpty()) {
            warnings.add("source file contents not supplied; inferred capabilities from file names only");
        }

        WorkflowAnalyzer workflowAnalyzer = new WorkflowAnalyzer();
        for (StudioNeedsExtractionSourceFile file : request.files()) {
            inferCapabilitiesFromSourceName(file.fileName(), required);
            if (file.content().isBlank()) {
                continue;
            }
            if (!isWorkflowSource(file.fileName())) {
                continue;
            }
            try {
                Need analyzed = workflowAnalyzer.analyzeContent(file.content(), file.fileName());
                analyzed.requiredCapabilities().forEach(requirement ->
                        addRequiredCapability(required, requirement.capability(), requirement.capabilityVersion().range()));
            } catch (WorkflowAnalysisException ex) {
                warnings.add(ex.getMessage());
            }
        }

        if (required.isEmpty()) {
            addRequiredCapability(required, fallbackApplicationCapability(request.targetApplicationName()), "^1");
            warnings.add(request.fileNames().isEmpty()
                    ? "no target application files supplied; generated starter needs"
                    : "no DCP capabilities could be inferred from supplied files; generated starter needs");
        }

        return new NeedsExtractionDraft(List.copyOf(required.values()), List.copyOf(warnings));
    }

    /**
     * Strategy helper: maps recognized source names to orchestration/runtime capabilities.
     *
     * @param fileName source file name or relative path.
     * @param required insertion-ordered capability accumulator.
     */
    private void inferCapabilitiesFromSourceName(
            String fileName,
            Map<String, CapabilityRequirement> required
    ) {
        if (isWorkflowSource(fileName)) {
            addRequiredCapability(required, "workflow.execute", "^1");
        }
        if (isAgentSource(fileName)) {
            addRequiredCapability(required, "agent.run", "^1");
        }
    }

    /**
     * Predicate helper: recognizes Flow workflow source names.
     *
     * @param fileName source file name or relative path.
     * @return true when the name convention implies a workflow needing Flow execution.
     */
    private boolean isWorkflowSource(String fileName) {
        String leaf = sourceLeaf(fileName);
        return leaf.equals("workflow.yaml")
                || leaf.equals("workflow.yml")
                || leaf.endsWith(".workflow.yaml")
                || leaf.endsWith(".workflow.yml")
                || leaf.endsWith(".flow.yaml")
                || leaf.endsWith(".flow.yml");
    }

    /**
     * Predicate helper: recognizes Foundry agent manifest source names.
     *
     * @param fileName source file name or relative path.
     * @return true when the name convention implies an agent needing Foundry execution.
     */
    private boolean isAgentSource(String fileName) {
        String lower = sourceName(fileName);
        String leaf = sourceLeaf(fileName);
        return leaf.equals("agent.yaml")
                || leaf.equals("agent.yml")
                || leaf.endsWith(".agent.yaml")
                || leaf.endsWith(".agent.yml")
                || lower.contains("/agents/");
    }

    /**
     * Normalizer helper: extracts a lower-case path leaf for source-name predicates.
     *
     * @param fileName source file name or relative path.
     * @return lower-case file leaf, or an empty string for blank input.
     */
    private String sourceLeaf(String fileName) {
        String normalized = sourceName(fileName);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * Normalizer helper: canonicalizes source paths without touching filesystem state.
     *
     * @param fileName source file name or relative path.
     * @return lower-case slash-separated source name.
     */
    private String sourceName(String fileName) {
        return fileName == null ? "" : fileName.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /**
     * Builder helper: adds a required capability once, preserving first-seen ordering.
     *
     * @param required     insertion-ordered capability accumulator.
     * @param capability   DCP capability name.
     * @param versionRange acceptable capability version range.
     */
    private void addRequiredCapability(
            Map<String, CapabilityRequirement> required,
            String capability,
            String versionRange
    ) {
        if (capability == null || capability.isBlank()) {
            return;
        }
        required.putIfAbsent(capability, CapabilityRequirement.requiredOf(capability, versionRange));
    }

    /**
     * Fallback helper: preserves the historical starter need for unknown source types.
     *
     * @param targetApplicationName operator-facing application label.
     * @return slugged application capability ending in `.run`.
     */
    private String fallbackApplicationCapability(String targetApplicationName) {
        String target = targetApplicationName == null ? "" : targetApplicationName;
        String capability = target
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.|\\.$", "");
        if (capability.isBlank()) {
            capability = "application";
        }
        return capability + ".run";
    }

    /** Value object: extracted needs requirements and warnings before response rendering. */
    private record NeedsExtractionDraft(
            List<CapabilityRequirement> requiredCapabilities,
            List<String> warnings
    ) {
    }

    public synchronized StudioAuthoringConverseResponse converseAuthoring(StudioAuthoringConverseRequest request) {
        StudioAuthoringConverseRequest safe = request == null
                ? new StudioAuthoringConverseRequest("tenant-local", "assembly-demo", "", List.of(), "")
                : request;
        String tenant = normalizeTenant(safe.tenantId());
        String assembly = normalizeAssembly(safe.assemblyId());
        StudioDraftSession session = authoringSession(tenant, assembly, safe.sessionId());

        if (authoringInvocable != null) {
            return converseViaDcp(safe, tenant, assembly, session);
        }

        String prompt = safe.userMessage().trim();
        String lowered = prompt.toLowerCase();

        if (prompt.length() < 12 || lowered.matches("^(build|make|create|app|service|contract)\\s*$")) {
            return StudioAuthoringConverseResponse.clarify(
                    session.sessionId(),
                    "I need a little more detail before I can propose a Fabric contract.",
                    List.of(
                            new StudioAuthoringQuestion(
                                    "capability",
                                    "What capability should the application provide?",
                                    "TEXT",
                                    List.of()),
                            new StudioAuthoringQuestion(
                                    "deploymentTarget",
                                    "Where should this run?",
                                    "SINGLE_SELECT",
                                    List.of("Customer Runtime Substrate", "Kubernetes production", "Local development"))));
        }

        if (lowered.contains("uncatalogued")
                || lowered.contains("uncataloged")
                || lowered.contains("not in catalog")
                || lowered.contains("quantum")) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "I could not find an admitted catalog component for that capability.",
                    List.of("uncatalogued.capability"));
        }

        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        if (entries.isEmpty()) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "This tenant does not have admitted catalog components to compose from.",
                    List.of("catalog.empty"));
        }

        StudioVisualCatalogEntry selected = entries.stream()
                .filter(entry -> promptMentionsEntry(prompt, entry))
                .findFirst()
                .orElse(entries.get(0));
        String capability = firstOfferedCapability(selected).orElse(slug(selected.catalogEntryId()).replace('-', '.'));
        String targetName = authoringTargetName(prompt);
        String needsYaml = """
                requiredCapabilities:
                  - capability: %s
                    capabilityVersion: ^1
                """.formatted(capability);

        Map<String, Object> addIntent = new LinkedHashMap<>();
        addIntent.put("type", "ADD_COMPONENT");
        addIntent.put("catalogEntryId", selected.catalogEntryId());

        List<Map<String, Object>> intents = List.of(addIntent);
        List<String> warnings = new ArrayList<>();
        Optional<StudioIntentResponse> rejection = rejectIntentAgainstCatalog(
                tenant,
                "ADD_COMPONENT",
                Map.of("catalogEntryId", selected.catalogEntryId()));
        if (rejection.isPresent()) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "The selected component is not currently admitted for this tenant.",
                    List.of(selected.catalogEntryId()));
        }
        if (safe.conversation().isEmpty()) {
            warnings.add("proposal generated from a single prompt; review before accepting");
        }

        StudioDeploymentPolicyDraft deploymentPolicy = new StudioDeploymentPolicyDraft(
                List.of("CONTAINERIZED_SERVICE"),
                List.of(),
                List.of(),
                new StudioDeploymentRuntimeDraft(null, null, true, null, null));
        StudioAuthoringProposal proposal = new StudioAuthoringProposal(
                needsYaml,
                intents,
                deploymentPolicy,
                warnings);
        return StudioAuthoringConverseResponse.proposal(
                session.sessionId(),
                "I found an admitted catalog component and prepared a reviewable Fabric proposal for " + targetName + ".",
                proposal);
    }

    /**
     * Inject the DCP authoring proposer (the foundry agent over {@code agent.run}, wired by a host).
     * When set, {@code converseAuthoring} routes through it instead of the deterministic bridge.
     */
    public StudioCatalogService useAuthoringInvocable(ContractInvocable invocable) {
        this.authoringInvocable = invocable;
        return this;
    }

    private StudioAuthoringConverseResponse converseViaDcp(
            StudioAuthoringConverseRequest request, String tenant, String assembly, StudioDraftSession session) {
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (StudioVisualCatalogEntry entry : entries) {
            List<String> capabilities = portsOfKind(entry.visualManifest(), "OFFER").stream()
                    .map(PortDescriptor::capability)
                    .toList();
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("catalogEntryId", entry.catalogEntryId());
            entryMap.put("displayName", entry.catalogEntryId());
            entryMap.put("offeredCapabilities", capabilities);
            catalog.add(entryMap);
        }
        List<Map<String, Object>> conversation = new ArrayList<>();
        for (StudioAuthoringConversationMessage message : request.conversation()) {
            conversation.add(Map.of("role", message.role(), "content", message.content()));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", tenant);
        input.put("assemblyId", assembly);
        input.put("sessionId", session.sessionId());
        input.put("userMessage", request.userMessage());
        input.put("conversation", conversation);
        input.put("catalog", catalog);

        ContractInvocation invocation = new ContractInvocation(
                "urn:unfurl:fabric:authoring", "agent.run", "unfurl-fabric", "unfurl-foundry",
                input, session.sessionId(), Map.of(), null, Map.of());
        ContractInvocationResult result = authoringInvocable.invoke(invocation, ExecutionContext.empty());
        if (!result.success()) {
            return StudioAuthoringConverseResponse.gap(session.sessionId(),
                    result.errorMessage() == null ? "the authoring agent could not respond." : result.errorMessage(),
                    List.of());
        }
        return mapAuthoringOutput(tenant, session.sessionId(), result.output());
    }

    private StudioAuthoringConverseResponse mapAuthoringOutput(String tenant, String sessionId, Map<String, Object> output) {
        String kind = String.valueOf(output.getOrDefault("kind", "clarify"));
        String message = output.get("assistantMessage") == null ? "" : String.valueOf(output.get("assistantMessage"));
        switch (kind) {
            case "proposal" -> {
                Map<String, Object> proposalMap = asMap(output.get("proposal"));
                String needsYaml = proposalMap.get("needsYaml") == null ? "" : String.valueOf(proposalMap.get("needsYaml"));
                List<Map<String, Object>> intents = asListOfMap(proposalMap.get("intents"));
                // Grounding guard: Fabric re-validates every proposed component against the admitted
                // catalog, regardless of what the agent/tools produced. An unadmitted component is a gap.
                for (Map<String, Object> intent : intents) {
                    Object entryId = intent.get("catalogEntryId");
                    if (entryId == null) {
                        continue;
                    }
                    String type = intent.get("type") == null ? "ADD_COMPONENT" : String.valueOf(intent.get("type"));
                    Optional<StudioIntentResponse> rejection = rejectIntentAgainstCatalog(
                            tenant, type, Map.of("catalogEntryId", String.valueOf(entryId)));
                    if (rejection.isPresent()) {
                        return StudioAuthoringConverseResponse.gap(sessionId,
                                "A proposed component is not admitted in this tenant's catalog.",
                                List.of(String.valueOf(entryId)));
                    }
                }
                StudioAuthoringProposal proposal = new StudioAuthoringProposal(
                        needsYaml,
                        intents,
                        new StudioDeploymentPolicyDraft(
                                List.of("CONTAINERIZED_SERVICE"), List.of(), List.of(),
                                new StudioDeploymentRuntimeDraft(null, null, true, null, null)),
                        List.of("proposal generated by the foundry authoring agent; review before accepting"));
                return StudioAuthoringConverseResponse.proposal(sessionId, message, proposal);
            }
            case "gap" -> {
                return StudioAuthoringConverseResponse.gap(sessionId, message, asStringList(output.get("unmet")));
            }
            default -> {
                List<StudioAuthoringQuestion> questions = new ArrayList<>();
                for (Map<String, Object> q : asListOfMap(output.get("questions"))) {
                    questions.add(new StudioAuthoringQuestion(
                            asString(q.get("id")),
                            asString(q.get("prompt")),
                            q.get("kind") == null ? "TEXT" : String.valueOf(q.get("kind")),
                            asStringList(q.get("options"))));
                }
                return StudioAuthoringConverseResponse.clarify(sessionId, message, questions);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMap(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> result.add(asString(item)));
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public StudioAssemblyListResponse listAssemblies(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, this::fixtureAssemblies);
        return new StudioAssemblyListResponse(tenant, assemblies.values().stream()
                .sorted(Comparator.comparing(StudioAssemblySummary::assemblyId))
                .toList());
    }

    public StudioDynamicDcpProjection dynamicDcpProjection(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, this::fixtureAssemblies);
        StudioAssemblySummary summary = assemblies.getOrDefault(assembly, fixtureAssemblies(tenant).get("assembly-demo"));
        String target = summary == null || summary.targetApplicationName().isBlank()
                ? "unfurl-flow"
                : summary.targetApplicationName();
        String rootNodeId = "company:" + slug(target);
        String focusNodeId = "assembly:" + slug(assembly);
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        Map<URI, StudioVisualCatalogEntry> entryByClaimUri = entries.stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> claimUriForEntry(entry.catalogEntryId()),
                        entry -> entry,
                        (left, right) -> left,
                        LinkedHashMap::new));
        URI rootClaimUri = URI.create("urn:unfurl:studio:" + rootNodeId);
        URI assemblyClaimUri = URI.create("urn:unfurl:studio:" + focusNodeId);
        Set<URI> nestedEntryClaimUris = new LinkedHashSet<>();
        entries.forEach(entry -> nestedEntryClaimUris.addAll(childClaimUrisForEntry(entry)));
        List<URI> assemblyChildClaimUris = entries.stream()
                .map(entry -> claimUriForEntry(entry.catalogEntryId()))
                .filter(uri -> !nestedEntryClaimUris.contains(uri))
                .sorted(Comparator.comparing(URI::toString))
                .toList();
        if (assemblyChildClaimUris.isEmpty()) {
            assemblyChildClaimUris = entries.stream()
                    .map(entry -> claimUriForEntry(entry.catalogEntryId()))
                    .sorted(Comparator.comparing(URI::toString))
                    .toList();
        }
        Map<URI, Claim> claims = new LinkedHashMap<>();
        claims.put(rootClaimUri, aggregateClaim(rootClaimUri, target, "COMPANY", "PARENT", List.of(assemblyClaimUri)));
        claims.put(assemblyClaimUri, aggregateClaim(
                assemblyClaimUri,
                summary == null || summary.targetApplicationName().isBlank()
                        ? "Unfurl Flow Assembly"
                        : summary.targetApplicationName() + " Assembly",
                "MODULE",
                "ASSEMBLY",
                assemblyChildClaimUris));
        entries.stream()
                .sorted(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId))
                .forEach(entry -> claims.put(claimUriForEntry(entry.catalogEntryId()), claimForEntry(entry)));

        DcpProjection dcpProjection = new DcpProjectionProjector().project(new DcpProjectionRequest(
                claims.get(rootClaimUri),
                claims,
                assemblyClaimUri,
                projectionMaxDepth(),
                projectionMaxNodes()));

        Map<URI, String> nodeIdsByClaimUri = new LinkedHashMap<>();
        nodeIdsByClaimUri.put(rootClaimUri, rootNodeId);
        nodeIdsByClaimUri.put(assemblyClaimUri, focusNodeId);
        entries.forEach(entry -> nodeIdsByClaimUri.put(claimUriForEntry(entry.catalogEntryId()), nodeIdForEntry(entry.catalogEntryId())));

        return studioProjectionFromDcp(
                tenant,
                assembly,
                rootNodeId,
                focusNodeId,
                dcpProjection,
                nodeIdsByClaimUri,
                entryByClaimUri,
                entries);
    }

    public StudioDynamicDcpProjection dynamicDcpProjection(
            String tenantId,
            String assemblyId,
            StudioDcpProjectionRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("projection request is required");
        }
        return dynamicDcpProjection(tenantId, assemblyId, request.rootUri(), request.focusUri(), request.claimMap());
    }

    public StudioDynamicDcpProjection dynamicDcpProjection(
            String tenantId,
            String assemblyId,
            URI rootClaimUri,
            URI focusClaimUri,
            Map<URI, Claim> claims
    ) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        if (rootClaimUri == null) {
            throw new IllegalArgumentException("rootClaimUri is required");
        }
        Map<URI, Claim> claimsByUri = new LinkedHashMap<>();
        if (claims != null) {
            claimsByUri.putAll(claims);
        }
        Claim rootClaim = claimsByUri.get(rootClaimUri);
        if (rootClaim == null) {
            throw new IllegalArgumentException("root claim is not loaded: " + rootClaimUri);
        }
        URI focus = focusClaimUri == null ? rootClaimUri : focusClaimUri;
        DcpProjection dcpProjection = new DcpProjectionProjector().project(new DcpProjectionRequest(
                rootClaim,
                claimsByUri,
                focus,
                projectionMaxDepth(),
                projectionMaxNodes()));

        Map<URI, String> nodeIdsByClaimUri = new LinkedHashMap<>();
        claimsByUri.keySet().stream()
                .sorted(Comparator.comparing(URI::toString))
                .forEach(uri -> nodeIdsByClaimUri.put(uri, nodeIdForClaimUri(uri)));

        return studioProjectionFromDcp(
                tenant,
                assembly,
                nodeIdsByClaimUri.getOrDefault(dcpProjection.rootClaimUri(), dcpProjection.rootClaimUri().toString()),
                nodeIdsByClaimUri.getOrDefault(dcpProjection.focusClaimUri(), dcpProjection.focusClaimUri().toString()),
                dcpProjection,
                nodeIdsByClaimUri,
                Map.of(),
                List.of());
    }

    private StudioDynamicDcpProjection studioProjectionFromDcp(
            String tenant,
            String assembly,
            String rootNodeId,
            String focusNodeId,
            DcpProjection dcpProjection,
            Map<URI, String> nodeIdsByClaimUri,
            Map<URI, StudioVisualCatalogEntry> entryByClaimUri,
            List<StudioVisualCatalogEntry> entries
    ) {
        List<StudioDynamicDcpNode> nodes = dcpProjection.nodes().stream()
                .map(node -> studioNodeFromDcp(node, nodeIdsByClaimUri, entryByClaimUri))
                .toList();
        List<StudioDynamicDcpNode> childNodes = nodes.stream()
                .filter(StudioDynamicDcpNode::replacementAllowed)
                .toList();
        List<StudioDynamicDcpEdge> edges = new ArrayList<>(dcpProjection.edges().stream()
                .map(edge -> studioEdgeFromDcp(edge, nodeIdsByClaimUri))
                .toList());
        List<String> childNodeIds = childNodes.stream().map(StudioDynamicDcpNode::nodeId).toList();
        for (int i = 0; i < childNodeIds.size() - 1; i++) {
            edges.add(new StudioDynamicDcpEdge(childNodeIds.get(i), childNodeIds.get(i + 1), "REQUIRES"));
        }

        List<StudioSubstratePort> substratePorts = deriveSubstratePorts(childNodes, entries);
        List<StudioPortConnectionEdge> connections = derivePortConnections(childNodes, entries, substratePorts);

        StudioDynamicDcpProjection projection = new StudioDynamicDcpProjection(
                tenant,
                assembly,
                "DYNAMIC",
                rootNodeId,
                focusNodeId,
                nodes,
                edges,
                substratePorts,
                connections,
                dcpProjection.warnings());
        return new StudioDynamicDcpProjection(
                projection.tenantId(),
                projection.assemblyId(),
                projection.compositionMode(),
                projection.rootNodeId(),
                projection.focusNodeId(),
                projection.nodes(),
                projection.edges(),
                projection.substratePorts(),
                projection.connections(),
                projection.warnings(),
                List.of(diagnosticArtifact(tenant, "dynamic-dcp", "dynamic-dcp.json", projection)));
    }

    private Claim aggregateClaim(
            URI claimUri,
            String label,
            String dcpType,
            String level,
            List<URI> childClaimUris
    ) {
        String capability = slug(label).replace('-', '.') + ".compose";
        return claim(
                claimUri,
                label,
                ComponentKind.INFRASTRUCTURE,
                dcpType,
                level,
                childClaimUris,
                List.of(capability),
                List.of());
    }

    /**
     * Adapter: reconstructs a DCP claim from a Studio visual catalog entry, including
     * raw manifest dependencies when the visual item originated from a real catalog claim.
     */
    private Claim claimForEntry(StudioVisualCatalogEntry entry) {
        Map<String, Object> dynamic = entry.dynamicComposition();
        return claim(
                claimUriForEntry(entry.catalogEntryId()),
                labelForCatalogEntry(entry.catalogEntryId()),
                ComponentKind.COMPONENT,
                stringValue(dynamic.get("dcpType"), "COMPONENT"),
                stringValue(dynamic.get("level"), "CHILD"),
                childClaimUrisForEntry(entry),
                capabilitiesFromVisual(entry.visualManifest()),
                rawNeedsForEntry(entry));
    }

    /**
     * Builder: assembles a synthetic DCP claim for Studio aggregate/visual-only entries
     * while keeping dependencies explicit for later candidate reconstruction.
     */
    private Claim claim(
            URI claimUri,
            String label,
            ComponentKind kind,
            String dcpType,
            String level,
            List<URI> childClaimUris,
            List<String> capabilities,
            List<String> dependencies
    ) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("dcpType", dcpType);
        extensions.put("level", level);
        extensions.put(DcpProjectionProjector.EXT_CONTAINS,
                childClaimUris.stream().map(URI::toString).sorted().toList());
        return new Claim(
                new Identity(claimUri, label, kind, "1.0.0", "Unfurl", URI.create("urn:unfurl")),
                null,
                List.of(),
                new Dependencies(dependencies == null ? List.of() : List.copyOf(dependencies)),
                capabilities.stream()
                        .distinct()
                        .sorted()
                        .map(capability -> new Offer(
                                capability,
                                capability,
                                ConsumerAccess.ANY,
                                new OfferInterface(InterfaceKind.IN_PROCESS, Map.of()),
                                Stability.STABLE,
                                "1.0.0",
                                false,
                                null))
                        .toList(),
                null,
                null,
                new IntegrationPorts(Map.of()),
                com.unfurl.dcp.fault.FaultPolicy.empty(),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, extensions));
    }

    /**
     * Projection helper: returns raw DCP dependency strings preserved from catalog
     * manifests/admitted claims.
     */
    private List<String> rawNeedsForEntry(StudioVisualCatalogEntry entry) {
        return stringList(entry.dynamicComposition().get("rawNeeds"));
    }

    private List<URI> childClaimUrisForEntry(StudioVisualCatalogEntry entry) {
        Map<String, Object> dynamic = entry.dynamicComposition();
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        addChildClaimUris(result, dynamic.get("contains"));
        addChildClaimUris(result, dynamic.get("children"));
        addChildClaimUris(result, dynamic.get("containsCatalogEntryIds"));
        addChildClaimUris(result, dynamic.get("childCatalogEntryIds"));
        addChildClaimUris(result, dynamic.get("containsClaimUris"));
        addChildClaimUris(result, dynamic.get("childClaimUris"));
        return result.stream().sorted(Comparator.comparing(URI::toString)).toList();
    }

    private void addChildClaimUris(Set<URI> result, Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            result.add(claimUriForReference(text));
            return;
        }
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                result.add(claimUriForReference(text));
            } else if (item instanceof Map<?, ?> map) {
                Object value = firstPresent(map, "catalogEntryId", "claimUri", "uri", "ref");
                if (value instanceof String text && !text.isBlank()) {
                    result.add(claimUriForReference(text));
                }
            }
        }
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private URI claimUriForReference(String value) {
        if (value.startsWith("urn:") || value.startsWith("http://") || value.startsWith("https://")) {
            return URI.create(value);
        }
        return claimUriForEntry(value);
    }

    private URI claimUriForEntry(String catalogEntryId) {
        return URI.create("urn:unfurl:catalog:" + slug(catalogEntryId));
    }

    private String nodeIdForClaimUri(URI claimUri) {
        String value = claimUri == null ? "" : claimUri.toString();
        String flowPrefix = "urn:unfurl:flow:";
        String foundryPrefix = "urn:unfurl:foundry:";
        if (value.startsWith(flowPrefix)) {
            return "flow." + slug(value.substring(flowPrefix.length()).replace(':', '.'));
        }
        if (value.startsWith(foundryPrefix)) {
            return "foundry." + slug(value.substring(foundryPrefix.length()).replace(':', '.'));
        }
        String urnPrefix = "urn:unfurl:";
        if (value.startsWith(urnPrefix)) {
            return "dcp." + slug(value.substring(urnPrefix.length()).replace(':', '.'));
        }
        return "dcp." + slug(value);
    }

    private StudioDynamicDcpNode studioNodeFromDcp(
            DcpProjectionNode node,
            Map<URI, String> nodeIdsByClaimUri,
            Map<URI, StudioVisualCatalogEntry> entryByClaimUri
    ) {
        StudioVisualCatalogEntry entry = entryByClaimUri.get(node.claimUri());
        String catalogEntryId = entry == null ? "" : entry.catalogEntryId();
        List<String> capabilities = entry == null
                ? node.offers()
                : capabilitiesFromVisual(entry.visualManifest());
        List<String> compatibleDescendants = entry == null
                ? List.of()
                : stringList(entry.dynamicComposition().get("compatibleDescendants"));
        return new StudioDynamicDcpNode(
                nodeIdsByClaimUri.getOrDefault(node.claimUri(), node.claimUri().toString()),
                node.label(),
                node.dcpType(),
                node.level(),
                node.parentClaimUri() == null ? null : nodeIdsByClaimUri.getOrDefault(node.parentClaimUri(), node.parentClaimUri().toString()),
                node.depth(),
                catalogEntryId,
                capabilities,
                compatibleDescendants,
                entry != null);
    }

    private StudioDynamicDcpEdge studioEdgeFromDcp(DcpProjectionEdge edge, Map<URI, String> nodeIdsByClaimUri) {
        return new StudioDynamicDcpEdge(
                nodeIdsByClaimUri.getOrDefault(edge.fromClaimUri(), edge.fromClaimUri().toString()),
                nodeIdsByClaimUri.getOrDefault(edge.toClaimUri(), edge.toClaimUri().toString()),
                edge.relationship());
    }

    private int projectionMaxDepth() {
        return intConfig("unfurl.studio.projection.maxDepth", "UNFURL_STUDIO_PROJECTION_MAX_DEPTH",
                DcpProjectionRequest.DEFAULT_MAX_DEPTH);
    }

    private int projectionMaxNodes() {
        return intConfig("unfurl.studio.projection.maxNodes", "UNFURL_STUDIO_PROJECTION_MAX_NODES",
                DcpProjectionRequest.DEFAULT_MAX_NODES);
    }

    private int intConfig(String property, String env, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Walk every pair of draft child nodes and emit a
     * {@link StudioPortConnectionEdge} whenever an OFFER port on one
     * node satisfies a DEPENDENCY port on another. Host-owned and
     * fabric-owned needs are external to the draft surface and stay
     * out of the resulting edge list (they would render as pipes that
     * dangle off-scene; the host's Spring context or the fabric framework
     * is what supplies them).
     *
     * <p>The matcher shares its capability-equality heuristic with
     * {@link #connectionCandidates} — both code paths consult the same
     * port descriptors and check {@code offer.capability.equals(need.capability)}.
     * Pairwise complexity is N² over draft component count, but the
     * draft is fixture-sized in practice (≤10 nodes) so the inner work
     * is negligible.
     */
    private List<StudioPortConnectionEdge> derivePortConnections(
            List<StudioDynamicDcpNode> childNodes,
            List<StudioVisualCatalogEntry> entries,
            List<StudioSubstratePort> substratePorts
    ) {
        Map<String, StudioVisualCatalogEntry> entriesById = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry entry : entries) {
            entriesById.put(entry.catalogEntryId(), entry);
        }
        // Pre-compute each node's ports so the inner loop doesn't re-walk
        // the visual manifest N times.
        Map<String, List<PortDescriptor>> offersByNodeId = new LinkedHashMap<>();
        Map<String, List<PortDescriptor>> needsByNodeId = new LinkedHashMap<>();
        Map<String, List<String>> rawNeedsByNodeId = new LinkedHashMap<>();
        for (StudioDynamicDcpNode node : childNodes) {
            StudioVisualCatalogEntry entry = entriesById.get(node.catalogEntryId());
            if (entry == null) {
                continue;
            }
            offersByNodeId.put(node.nodeId(), portsOfKind(entry.visualManifest(), "OFFER"));
            needsByNodeId.put(node.nodeId(), portsOfKind(entry.visualManifest(), "DEPENDENCY"));
            rawNeedsByNodeId.put(node.nodeId(), rawDependencyStrings(entry));
        }
        Map<String, String> labelByNodeId = new LinkedHashMap<>();
        for (StudioDynamicDcpNode node : childNodes) {
            labelByNodeId.put(node.nodeId(), node.label());
        }
        Set<String> substrateCapabilities = new LinkedHashSet<>();
        Map<String, StudioSubstratePort> substrateByCapability = new LinkedHashMap<>();
        for (StudioSubstratePort port : substratePorts) {
            substrateCapabilities.add(port.capability());
            substrateByCapability.put(port.capability(), port);
        }

        List<StudioPortConnectionEdge> connections = new ArrayList<>();
        for (StudioDynamicDcpNode consumer : childNodes) {
            List<PortDescriptor> needs = needsByNodeId.getOrDefault(consumer.nodeId(), List.of());
            if (needs.isEmpty()) {
                continue;
            }
            Set<String> externalNeeds = externallyOwnedNeeds(
                    rawNeedsByNodeId.getOrDefault(consumer.nodeId(), List.of()));
            for (PortDescriptor need : needs) {
                if (substrateCapabilities.contains(need.capability())) {
                    StudioSubstratePort substrate = substrateByCapability.get(need.capability());
                    connections.add(new StudioPortConnectionEdge(
                            "substrate:runtime",
                            substrate.portId(),
                            consumer.nodeId(),
                            need.id(),
                            need.capability(),
                            "ALLOWED",
                            "Unfurl substrate offers " + need.capability()
                                    + " required by "
                                    + labelByNodeId.getOrDefault(consumer.nodeId(), consumer.nodeId())));
                    continue;
                }
                if (externalNeeds.contains(need.capability())) {
                    continue;
                }
                for (StudioDynamicDcpNode provider : childNodes) {
                    if (provider.nodeId().equals(consumer.nodeId())) {
                        continue;
                    }
                    List<PortDescriptor> offers = offersByNodeId.getOrDefault(provider.nodeId(), List.of());
                    for (PortDescriptor offer : offers) {
                        if (offer.capability().equals(need.capability())) {
                            connections.add(new StudioPortConnectionEdge(
                                    provider.nodeId(),
                                    offer.id(),
                                    consumer.nodeId(),
                                    need.id(),
                                    offer.capability(),
                                    "ALLOWED",
                                    labelByNodeId.getOrDefault(provider.nodeId(), provider.nodeId())
                                            + " offers " + offer.capability()
                                            + " required by "
                                            + labelByNodeId.getOrDefault(consumer.nodeId(), consumer.nodeId())));
                        }
                    }
                }
            }
        }
        connections.sort(Comparator
                .comparing(StudioPortConnectionEdge::sourceNodeId)
                .thenComparing(StudioPortConnectionEdge::sourcePortId)
                .thenComparing(StudioPortConnectionEdge::targetNodeId)
                .thenComparing(StudioPortConnectionEdge::targetPortId));
        return connections;
    }

    private List<StudioSubstratePort> deriveSubstratePorts(
            List<StudioDynamicDcpNode> childNodes,
            List<StudioVisualCatalogEntry> entries
    ) {
        Map<String, StudioVisualCatalogEntry> entriesById = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry entry : entries) {
            entriesById.put(entry.catalogEntryId(), entry);
        }
        Set<String> offeredCapabilities = new LinkedHashSet<>();
        for (StudioDynamicDcpNode node : childNodes) {
            StudioVisualCatalogEntry entry = entriesById.get(node.catalogEntryId());
            if (entry == null) {
                continue;
            }
            for (PortDescriptor offer : portsOfKind(entry.visualManifest(), "OFFER")) {
                offeredCapabilities.add(offer.capability());
            }
        }
        Map<String, StudioSubstratePort> ports = new LinkedHashMap<>();
        for (StudioDynamicDcpNode node : childNodes) {
            StudioVisualCatalogEntry entry = entriesById.get(node.catalogEntryId());
            if (entry == null) {
                continue;
            }
            Map<String, String> rawNeedsByCapability = rawNeedsByCapability(entry);
            for (String rawNeed : rawDependencyStrings(entry)) {
                String capability = capabilityNameFromDependencyUri(rawNeed);
                if (capability == null || capability.isBlank()) {
                    continue;
                }
                if (!isSubstrateOwned(rawNeed)
                        && (offeredCapabilities.contains(capability)
                        || !isUnfurlSubstrateCapability(capability))) {
                    continue;
                }
                String portId = "substrate:" + capability.replace('.', '-');
                ports.putIfAbsent(capability, new StudioSubstratePort(
                        portId,
                        capability,
                        substrateLabel(capability),
                        queryParam(rawNeed, "provider", "unfurl-substrate"),
                        "AVAILABLE"));
            }
            for (PortDescriptor need : portsOfKind(entry.visualManifest(), "DEPENDENCY")) {
                if (offeredCapabilities.contains(need.capability())
                        || !isUnfurlSubstrateCapability(need.capability())) {
                    continue;
                }
                String rawNeed = rawNeedsByCapability.get(need.capability());
                String portId = "substrate:" + need.capability().replace('.', '-');
                ports.putIfAbsent(need.capability(), new StudioSubstratePort(
                        portId,
                        need.capability(),
                        substrateLabel(need.capability()),
                        queryParam(rawNeed, "provider", "unfurl-substrate"),
                        "AVAILABLE"));
            }
        }
        return ports.values().stream()
                .sorted(Comparator.comparing(StudioSubstratePort::capability))
                .toList();
    }

    private Map<String, String> rawNeedsByCapability(StudioVisualCatalogEntry entry) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (String need : rawDependencyStrings(entry)) {
            String capability = capabilityNameFromDependencyUri(need);
            if (capability != null && !capability.isBlank()) {
                raw.putIfAbsent(capability, need);
            }
        }
        return raw;
    }

    /**
     * Pull the raw {@code claim.dependencies.needs[]} strings off a
     * catalog entry — we need the unparsed form to detect the
     * {@code ?owner=host} / {@code ?owner=fabric} markers that the
     * derived port matcher skips. The fixture catalog stores the claim
     * inside the visual manifest's metadata only for entries scanned
     * from real manifests; bundled fixtures don't carry the raw needs,
     * which is fine because they don't declare any.
     */
    private List<String> rawDependencyStrings(StudioVisualCatalogEntry entry) {
        Map<String, Object> dynamicComposition = entry.dynamicComposition();
        if (dynamicComposition == null) {
            return List.of();
        }
        Object rawNeeds = dynamicComposition.get("rawNeeds");
        if (rawNeeds instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /**
     * Return the set of capability names whose declared {@code needs}
     * carry an {@code ?owner=host} or {@code ?owner=fabric} marker —
     * these are external dependencies and should not surface as in-scene
     * pipes. Detection mirrors the same skip logic in
     * {@link com.unfurl.fabric.matcher.CandidateValidator}.
     */
    private Set<String> externallyOwnedNeeds(List<String> rawDeps) {
        Set<String> external = new LinkedHashSet<>();
        for (String dep : rawDeps) {
            if (dep == null) {
                continue;
            }
            if (dep.contains("owner=host") || dep.contains("owner=fabric")) {
                String cap = capabilityNameFromDependencyUri(dep);
                if (cap != null) {
                    external.add(cap);
                }
            }
        }
        return external;
    }

    private static boolean isSubstrateOwned(String dep) {
        return dep != null && (dep.contains("substrate=true") || dep.contains("owner=substrate"));
    }

    private static boolean isUnfurlSubstrateCapability(String capability) {
        return capability != null && (
                capability.startsWith("substrate.")
                        || capability.startsWith("spring-ai.")
                        || capability.startsWith("workflow.contract.")
                        || capability.equals("rag.corpus")
                        || capability.equals("tool.implementation")
                        || capability.equals("state-store")
                        || capability.equals("event-sink")
                        || capability.equals("secrets.provider")
                        || capability.equals("telemetry.otel"));
    }

    public StudioReplacementCandidatesResponse replacementCandidates(
            String tenantId,
            String assemblyId,
            String componentNodeId
    ) {
        StudioDynamicDcpProjection projection = dynamicDcpProjection(tenantId, assemblyId);
        String selectedNodeId = componentNodeId == null || componentNodeId.isBlank()
                ? "component.validation-service"
                : componentNodeId;
        StudioDynamicDcpNode selected = projection.nodes().stream()
                .filter(node -> node.nodeId().equals(selectedNodeId))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return new StudioReplacementCandidatesResponse(
                    projection.tenantId(),
                    projection.assemblyId(),
                    selectedNodeId,
                    List.of(),
                    List.of("selected DCP node is not present in this assembly projection"));
        }
        if (!selected.replacementAllowed()) {
            return new StudioReplacementCandidatesResponse(
                    projection.tenantId(),
                    projection.assemblyId(),
                    selectedNodeId,
                    List.of(new StudioReplacementCandidate(
                            selected.catalogEntryId(),
                            selected.label(),
                            "fabric",
                            selected.dcpType(),
                            "BLOCKED",
                            "selected DCP node is governed by the parent composition and cannot be replaced directly")),
                    List.of());
        }

        List<StudioReplacementCandidate> candidates = new ArrayList<>();
        candidates.add(new StudioReplacementCandidate(
                selected.catalogEntryId(),
                selected.label(),
                "current-selection",
                selected.dcpType(),
                "ALLOWED",
                "current selected component remains valid for this dynamic DCP slot"));
        for (String descendant : selected.compatibleDescendants()) {
            candidates.add(candidateForDescendant(descendant, selected));
        }
        candidates.sort(Comparator
                .comparing((StudioReplacementCandidate candidate) -> "BLOCKED".equals(candidate.status()) ? 1 : 0)
                .thenComparing(StudioReplacementCandidate::label));
        return new StudioReplacementCandidatesResponse(
                projection.tenantId(),
                projection.assemblyId(),
                selectedNodeId,
                candidates,
                List.of());
    }

    /**
     * Hover-preview compatibility surface for the Studio palette.
     *
     * <p>Given a candidate catalog entry the operator is hovering, walks
     * the current draft projection and reports two kinds of edge:
     * <ul>
     *   <li><b>connection</b> — an OFFER↔DEPENDENCY pairing between the
     *       candidate and an existing draft node. Direction is
     *       {@code CANDIDATE_OFFERS} when the candidate's offer satisfies
     *       a draft node's dependency, {@code CANDIDATE_NEEDS} when a
     *       draft node's offer satisfies the candidate's dependency.</li>
     *   <li><b>replacement</b> — the candidate appears in some draft
     *       node's {@code compatibleDescendants}, so it could substitute
     *       that slot wholesale instead of plugging into it.</li>
     * </ul>
     *
     * <p>Self-edges (candidate already in the draft) are suppressed. An
     * unknown candidate id is reported as an empty response with a
     * warning rather than thrown — same pattern as
     * {@link #replacementCandidates(String, String, String)} so the UI's
     * hover handler doesn't have to special-case 4xx flows.
     */
    public StudioConnectionCandidatesResponse connectionCandidates(
            String tenantId,
            String assemblyId,
            String catalogEntryId
    ) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        String candidateId = catalogEntryId == null ? "" : catalogEntryId.trim();
        if (candidateId.isBlank()) {
            return new StudioConnectionCandidatesResponse(
                    tenant, assembly, "",
                    List.of(), List.of(),
                    List.of("catalogEntryId is required"));
        }

        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        StudioVisualCatalogEntry candidate = entries.stream()
                .filter(entry -> candidateId.equals(entry.catalogEntryId()))
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return new StudioConnectionCandidatesResponse(
                    tenant, assembly, candidateId,
                    List.of(), List.of(),
                    List.of("catalogEntryId " + candidateId + " is not in the tenant catalog"));
        }

        StudioDynamicDcpProjection projection = dynamicDcpProjection(tenant, assembly);
        Map<String, StudioVisualCatalogEntry> entriesById = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry entry : entries) {
            entriesById.put(entry.catalogEntryId(), entry);
        }
        List<PortDescriptor> candidateOffers = portsOfKind(candidate.visualManifest(), "OFFER");
        List<PortDescriptor> candidateNeeds = portsOfKind(candidate.visualManifest(), "DEPENDENCY");

        List<StudioConnectionEdge> connections = new ArrayList<>();
        for (StudioDynamicDcpNode node : projection.nodes()) {
            if (!"COMPONENT".equals(node.dcpType())) {
                continue;
            }
            if (candidateId.equals(node.catalogEntryId())) {
                continue;
            }
            StudioVisualCatalogEntry draftEntry = entriesById.get(node.catalogEntryId());
            if (draftEntry == null) {
                continue;
            }
            List<PortDescriptor> draftOffers = portsOfKind(draftEntry.visualManifest(), "OFFER");
            List<PortDescriptor> draftNeeds = portsOfKind(draftEntry.visualManifest(), "DEPENDENCY");

            // Candidate offers vs draft needs.
            for (PortDescriptor offer : candidateOffers) {
                for (PortDescriptor need : draftNeeds) {
                    if (offer.capability.equals(need.capability)) {
                        connections.add(new StudioConnectionEdge(
                                node.nodeId(),
                                need.id,
                                offer.id,
                                "CANDIDATE_OFFERS",
                                "ALLOWED",
                                "candidate offers " + offer.capability
                                        + " required by " + node.label()));
                    }
                }
            }
            // Candidate needs vs draft offers.
            for (PortDescriptor need : candidateNeeds) {
                for (PortDescriptor offer : draftOffers) {
                    if (need.capability.equals(offer.capability)) {
                        connections.add(new StudioConnectionEdge(
                                node.nodeId(),
                                offer.id,
                                need.id,
                                "CANDIDATE_NEEDS",
                                "ALLOWED",
                                node.label() + " offers " + offer.capability
                                        + " required by candidate"));
                    }
                }
            }
        }
        connections.sort(Comparator
                .comparing(StudioConnectionEdge::targetNodeId)
                .thenComparing(StudioConnectionEdge::targetPortId)
                .thenComparing(StudioConnectionEdge::candidatePortId));

        // Replacement edges: a draft node whose compatibleDescendants list
        // contains the candidate's derived nodeId could be substituted by
        // the candidate. The derived nodeId mirrors nodeIdForEntry's slug
        // convention so the comparison is symmetric with the existing
        // dynamicNodeForEntry projection.
        String candidateNodeId = nodeIdForEntry(candidateId);
        List<StudioReplacementEdge> replacements = new ArrayList<>();
        for (StudioDynamicDcpNode node : projection.nodes()) {
            if (!"COMPONENT".equals(node.dcpType())) {
                continue;
            }
            if (candidateId.equals(node.catalogEntryId())) {
                continue;
            }
            if (node.compatibleDescendants().contains(candidateNodeId)) {
                replacements.add(new StudioReplacementEdge(
                        node.nodeId(),
                        "ALLOWED",
                        node.label() + " lists candidate as a compatible descendant"));
            }
        }
        replacements.sort(Comparator.comparing(StudioReplacementEdge::targetNodeId));

        return new StudioConnectionCandidatesResponse(
                tenant, assembly, candidateId,
                connections, replacements,
                List.of());
    }

    /**
     * Pull ports of a given {@code kind} ("OFFER" or "DEPENDENCY") off a
     * visual manifest's raw {@code ports} list, projecting each into a
     * tiny descriptor record. The capability name is derived from
     * {@code mapsTo} by stripping the {@code claim.offers.} / {@code
     * claim.dependencies.} prefix written by {@link #visual} — that
     * keeps this method symmetric with the projection side without
     * needing a separate capability accessor on the port map.
     */
    private List<PortDescriptor> portsOfKind(Map<String, Object> visualManifest, String kind) {
        if (visualManifest == null) {
            return List.of();
        }
        Object ports = visualManifest.get("ports");
        if (!(ports instanceof List<?> list)) {
            return List.of();
        }
        String prefix = "OFFER".equals(kind) ? "claim.offers." : "claim.dependencies.";
        List<PortDescriptor> out = new ArrayList<>();
        for (Object portObj : list) {
            if (!(portObj instanceof Map<?, ?> port)) {
                continue;
            }
            if (!kind.equals(stringValue(port.get("kind"), ""))) {
                continue;
            }
            String id = stringValue(port.get("id"), "");
            String mapsTo = stringValue(port.get("mapsTo"), "");
            if (id.isBlank() || !mapsTo.startsWith(prefix)) {
                continue;
            }
            String capability = mapsTo.substring(prefix.length());
            if (capability.isBlank()) {
                continue;
            }
            out.add(new PortDescriptor(id, capability));
        }
        return out;
    }

    private record PortDescriptor(String id, String capability) {
    }

    public StudioAssemblySummary createAssembly(String tenantId, StudioCreateAssemblyRequest request) {
        String tenant = normalizeTenant(tenantId);
        if (request == null) {
            throw new IllegalArgumentException("assembly request is required");
        }
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, this::fixtureAssemblies);
        StudioAssemblySummary summary = new StudioAssemblySummary(
                tenant,
                request.assemblyId(),
                request.targetApplicationName(),
                request.defaultDeploymentTarget(),
                "",
                "CONTAINERIZED_SERVICE",
                "",
                0);
        assemblies.put(summary.assemblyId(), summary);
        persist();
        return summary;
    }

    public StudioSaveDraftResponse saveDraft(String tenantId, String assemblyId, StudioSaveDraftRequest request) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        StudioSaveDraftRequest safeRequest = request == null
                ? new StudioSaveDraftRequest("", "", "", "CONTAINERIZED_SERVICE", "", 0)
                : request;
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, this::fixtureAssemblies);
        StudioAssemblySummary previous = assemblies.get(assembly);
        StudioAssemblySummary saved = new StudioAssemblySummary(
                tenant,
                assembly,
                safeRequest.targetApplicationName().isBlank() && previous != null
                        ? previous.targetApplicationName()
                        : safeRequest.targetApplicationName(),
                safeRequest.deploymentTarget().isBlank() && previous != null
                        ? previous.defaultDeploymentTarget()
                        : safeRequest.deploymentTarget(),
                safeRequest.needsId(),
                safeRequest.deploymentShape(),
                safeRequest.currentCandidateId(),
                safeRequest.sceneRevision());
        assemblies.put(assembly, saved);
        persist();
        StudioSaveDraftResponse response = new StudioSaveDraftResponse("SAVED", saved, List.of());
        return new StudioSaveDraftResponse(
                response.status(),
                response.assembly(),
                response.warnings(),
                List.of(diagnosticArtifact(tenant, "saved-draft", "saved-draft.json", response)));
    }

    /**
     * Snapshot factory: captures the durable Studio workspace state for one
     * tenant/assembly. The result is a portable JSON object; compile/export
     * validity remains governed by later Fabric DCP operations.
     */
    public StudioAssemblySnapshot saveAssemblySnapshot(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = normalizeAssembly(assemblyId);
        StudioAssemblySummary summary = assembliesByTenant
                .computeIfAbsent(tenant, this::fixtureAssemblies)
                .getOrDefault(assembly, defaultAssemblySummary(tenant, assembly));
        StudioLayoutState layoutState = layout(tenant, assembly);
        List<StudioDraftSession> sessions = sessionsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .values()
                .stream()
                .filter(session -> assembly.equals(session.assemblyId()))
                .sorted(Comparator.comparing(StudioDraftSession::sessionId))
                .toList();
        StudioAssemblySnapshot snapshot = new StudioAssemblySnapshot(
                tenant,
                assembly,
                summary,
                layoutState,
                sessions,
                List.of());
        return new StudioAssemblySnapshot(
                snapshot.tenantId(),
                snapshot.assemblyId(),
                snapshot.assembly(),
                snapshot.layout(),
                snapshot.sessions(),
                List.of(diagnosticArtifact(tenant, "assembly-snapshot", "assembly-snapshot.json", snapshot)));
    }

    /**
     * Snapshot loader: restores a portable assembly snapshot into the addressed
     * tenant and assembly maps. Route ids are authoritative and all imported
     * sessions are re-scoped before persistence.
     */
    public StudioAssemblySnapshot loadAssemblySnapshot(
            String tenantId,
            String assemblyId,
            StudioAssemblySnapshot snapshot
    ) {
        String tenant = normalizeTenant(tenantId);
        String assembly = normalizeAssembly(assemblyId);
        if (snapshot == null) {
            throw new IllegalArgumentException("assembly snapshot is required");
        }
        StudioAssemblySummary sourceAssembly = snapshot.assembly() == null
                ? defaultAssemblySummary(tenant, assembly)
                : snapshot.assembly();
        StudioAssemblySummary normalizedAssembly = new StudioAssemblySummary(
                tenant,
                assembly,
                sourceAssembly.targetApplicationName(),
                sourceAssembly.defaultDeploymentTarget(),
                sourceAssembly.needsId(),
                sourceAssembly.deploymentShape(),
                sourceAssembly.currentCandidateId(),
                sourceAssembly.sceneRevision());
        assembliesByTenant.computeIfAbsent(tenant, this::fixtureAssemblies).put(assembly, normalizedAssembly);

        StudioLayoutState sourceLayout = snapshot.layout() == null
                ? layout(tenant, assembly)
                : snapshot.layout();
        StudioLayoutState normalizedLayout = new StudioLayoutState(
                tenant,
                assembly,
                sourceLayout.activeView(),
                sourceLayout.semanticZoomLevel(),
                sourceLayout.selectedSurface(),
                sourceLayout.camera(),
                sourceLayout.annotations());
        layoutsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(assembly, normalizedLayout);

        Map<String, StudioDraftSession> tenantSessions = sessionsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        tenantSessions.keySet().removeIf(key -> key.startsWith(assembly + "/"));
        List<StudioDraftSession> normalizedSessions = snapshot.sessions().stream()
                .map(session -> new StudioDraftSession(
                        tenant,
                        assembly,
                        session.sessionId(),
                        session.baseCatalogHash(),
                        session.compositionMode(),
                        session.needsId(),
                        session.trustPolicyId(),
                        session.currentCandidateId(),
                        session.sceneRevision(),
                        session.warnings(),
                        session.collaborators(),
                        session.intentLog()))
                .sorted(Comparator.comparing(StudioDraftSession::sessionId))
                .toList();
        normalizedSessions.forEach(session -> tenantSessions.put(sessionKey(assembly, session.sessionId()), session));
        persist();
        StudioAssemblySnapshot loaded = new StudioAssemblySnapshot(
                tenant,
                assembly,
                normalizedAssembly,
                normalizedLayout,
                normalizedSessions,
                List.of());
        return new StudioAssemblySnapshot(
                loaded.tenantId(),
                loaded.assemblyId(),
                loaded.assembly(),
                loaded.layout(),
                loaded.sessions(),
                List.of(diagnosticArtifact(tenant, "assembly-snapshot-load", "assembly-snapshot-load.json", loaded)));
    }

    public StudioLayoutState layout(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        Map<String, StudioLayoutState> layouts = layoutsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        return layouts.computeIfAbsent(assembly, ignored -> new StudioLayoutState(
                tenant,
                assembly,
                "Assembly",
                "ASSEMBLY_DCP",
                "validation",
                Map.of(),
                List.of()));
    }

    public StudioLayoutState saveLayout(String tenantId, String assemblyId, StudioLayoutStateRequest request) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        StudioLayoutStateRequest safe = request == null
                ? new StudioLayoutStateRequest("Assembly", "ASSEMBLY_DCP", "validation", Map.of(), List.of())
                : request;
        StudioLayoutState state = new StudioLayoutState(
                tenant,
                assembly,
                safe.activeView(),
                safe.semanticZoomLevel(),
                safe.selectedSurface(),
                safe.camera(),
                safe.annotations());
        layoutsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(assembly, state);
        persist();
        return state;
    }

    public synchronized StudioCreateDraftCompositionResponse createDraftSession(
            String tenantId,
            String assemblyId,
            StudioCreateDraftCompositionRequest request
    ) {
        String tenant = normalizeTenant(tenantId);
        String assembly = normalizeAssembly(assemblyId);
        StudioCreateDraftCompositionRequest safe = request == null
                ? new StudioCreateDraftCompositionRequest(tenant, assembly, "", "", "", "", "", "")
                : request;
        String sessionId = "studio-session-" + UUID.randomUUID();
        StudioDraftSession session = new StudioDraftSession(
                tenant,
                assembly,
                sessionId,
                stringValue(safe.baseCatalogHash(), response(entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries)).catalogHash()),
                "DYNAMIC",
                safe.needsId(),
                safe.trustPolicyId(),
                safe.initialCandidateId(),
                0,
                List.of(),
                List.of(collaborator(safe.collaboratorId(), safe.collaboratorName(), "")),
                List.of());
        sessionsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(sessionKey(assembly, sessionId), session);
        persist();
        publishSessionEvent(session);
        StudioCreateDraftCompositionResponse response = new StudioCreateDraftCompositionResponse(session);
        return new StudioCreateDraftCompositionResponse(
                session,
                List.of(diagnosticArtifact(tenant, "draft-session", "draft-session.json", response)));
    }

    public synchronized StudioDraftSession draftSession(String tenantId, String assemblyId, String sessionId) {
        String tenant = normalizeTenant(tenantId);
        String key = sessionKey(normalizeAssembly(assemblyId), sessionId);
        StudioDraftSession session = sessionsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .get(key);
        if (session == null) {
            throw new IllegalArgumentException("Studio draft session not found: " + sessionId);
        }
        return pruneCollaborators(session);
    }

    public synchronized StudioSessionEvent sessionEvent(String tenantId, String assemblyId, String sessionId) {
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        return eventForSession(session);
    }

    public StudioSessionEventSubscription subscribeSessionEvents(String tenantId, String assemblyId, String sessionId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = normalizeAssembly(assemblyId);
        StudioSessionEvent initial;
        try {
            initial = sessionEvent(tenant, assembly, sessionId);
        } catch (IllegalArgumentException ex) {
            initial = null;
        }
        String key = sessionEventKey(tenant, assembly, sessionId);
        return eventBus.subscribe(key, initial);
    }

    public StudioEventBusHealth eventBusHealth() {
        return eventBus.health();
    }

    private StudioSessionEvent eventForSession(StudioDraftSession session) {
        return new StudioSessionEvent(
                session.sessionId() + ":" + session.sceneRevision(),
                "session",
                session,
                Instant.now());
    }

    public synchronized StudioIntentResponse applyIntent(String tenantId, String assemblyId, String sessionId, StudioIntentRequest request) {
        StudioDraftSession current = draftSession(tenantId, assemblyId, sessionId);
        if (request == null || request.type == null || request.type.isBlank()) {
            return StudioIntentResponse.invalid("intent type is required", "Studio intents must name a governed operation");
        }
        if (request.baseRevision != current.sceneRevision()) {
            return StudioIntentResponse.stale(current.sceneRevision(), request.baseRevision, current);
        }

        long revision = current.sceneRevision() + 1;
        Map<String, Object> payload = new LinkedHashMap<>(request.payload());
        // Defence-in-depth: only components present in the tenant's catalog
        // are assemblable. Mirrors the UI guard in DraftWorkspacePanel.
        Optional<StudioIntentResponse> rejection = rejectIntentAgainstCatalog(
                current.tenantId(), request.type, payload);
        if (rejection.isPresent()) {
            return rejection.get();
        }
        String candidateId = candidateAfterIntent(current.currentCandidateId(), request, payload);
        StudioIntentRecord record = new StudioIntentRecord(
                revision,
                collaboratorId(request.collaboratorId),
                request.type,
                payload,
                Instant.now());
        List<StudioIntentRecord> intentLog = new ArrayList<>(current.intentLog());
        intentLog.add(record);
        StudioDraftSession updated = new StudioDraftSession(
                current.tenantId(),
                current.assemblyId(),
                current.sessionId(),
                current.baseCatalogHash(),
                current.compositionMode(),
                current.needsId(),
                current.trustPolicyId(),
                candidateId,
                revision,
                current.warnings(),
                upsertCollaborator(current.collaborators(), request.collaboratorId, request.collaboratorName, stringValue(payload.get("selectedSurface"), "")),
                intentLog);
        putSession(updated);
        updateAssemblyRevision(updated);
        persist();
        publishSessionEvent(updated);
        return StudioIntentResponse.valid(revision, candidateId, updated);
    }

    public synchronized StudioDraftSession heartbeat(
            String tenantId,
            String assemblyId,
            String sessionId,
            StudioCollaborator collaborator
    ) {
        StudioDraftSession current = draftSession(tenantId, assemblyId, sessionId);
        StudioDraftSession updated = new StudioDraftSession(
                current.tenantId(),
                current.assemblyId(),
                current.sessionId(),
                current.baseCatalogHash(),
                current.compositionMode(),
                current.needsId(),
                current.trustPolicyId(),
                current.currentCandidateId(),
                current.sceneRevision(),
                current.warnings(),
                upsertCollaborator(current.collaborators(), collaborator.collaboratorId(), collaborator.displayName(), collaborator.selectedSurface()),
                current.intentLog());
        putSession(updated);
        persist();
        publishSessionEvent(updated);
        return updated;
    }

    /**
     * Application service: resolves deployment shapes for the full draft inventory in a
     * Studio session.
     *
     * <p>Pattern: facade over session replay, needs lookup, candidate validation, and the
     * shared deployment resolver. This is the UI path for `/studio/deployment/resolve`;
     * browser clients never pass filesystem catalog paths.
     */
    public synchronized StudioDeploymentResolveResponse resolveDeployment(StudioDeploymentResolveRequest request) {
        if (request == null || !request.usesSessionState()) {
            throw new IllegalArgumentException("session deployment resolve requires tenantId, assemblyId, and sessionId");
        }
        StudioDraftSession session = draftSession(request.tenantId(), request.assemblyId(), request.sessionId());
        Need need = needForSession(session, request.needsId(), request.needsYaml());
        CandidateBuild build = candidateForSession(session, need);
        if (build.invalid() != null) {
            return StudioDeploymentResolveResponse.invalid(
                    build.invalid().reason(),
                    build.invalid().details(),
                    List.of());
        }
        return deploymentService.resolveDeployment(build.candidate(), request);
    }

    /**
     * Application service: compiles the complete validated Studio draft inventory into
     * exportable Fabric artifacts.
     *
     * <p>Pattern: facade over intent-log replay, DCP candidate validation, deployment
     * shape resolution, contract compilation, substrate profile derivation, optional
     * operator signing, and hash-pinned export artifact storage.
     */
    public synchronized StudioCompileDraftCandidateResponse compileCandidate(
            String tenantId,
            String assemblyId,
            String sessionId,
            StudioCompileDraftCandidateRequest request
    ) {
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        long expected = request == null ? session.sceneRevision() : request.expectedRevision();
        if (expected != session.sceneRevision()) {
            StudioCompileDraftCandidateResponse response = new StudioCompileDraftCandidateResponse(
                    "STALE_REVISION",
                    "",
                    null,
                    null,
                    null,
                    List.of(),
                    "",
                    "",
                    session.sceneRevision(),
                    expected);
            return new StudioCompileDraftCandidateResponse(
                    response.status(),
                    response.candidateId(),
                    response.contractArtifact(),
                    response.substrateProfileArtifact(),
                    response.signedContractArtifact(),
                    response.warnings(),
                    response.reason(),
                    response.details(),
                    response.expectedRevision(),
                    response.receivedRevision(),
                    List.of(diagnosticArtifact(session.tenantId(), "compile-stale", "compile-response.json", response)));
        }

        Need need = needForSession(session, "", "");
        CandidateBuild build = candidateForSession(session, need);
        if (build.invalid() != null) {
            return invalidCompileResponse(session, build.invalid(), expected);
        }

        ResolverOutcome resolved;
        try {
            resolved = deploymentService.resolveOutcome(build.candidate(),
                    request == null ? null : request.deploymentPolicy());
        } catch (RuntimeException ex) {
            return invalidCompileResponse(
                    session,
                    new InvalidDraft("DEPLOYMENT_RESOLUTION_FAILED", ex.getMessage()),
                    expected);
        }

        CompiledArtifacts compiled;
        try {
            compiled = compileArtifacts(build.candidate(), need, resolved);
        } catch (RuntimeException ex) {
            return invalidCompileResponse(
                    session,
                    new InvalidDraft("COMPILE_FAILED", ex.getMessage()),
                    expected);
        }

        List<String> warnings = new ArrayList<>(build.warnings());
        StudioExportArtifact signed = null;
        if (request != null && request.sign()) {
            SignedArtifact signedArtifact = signCompiledContract(compiled.contract());
            if (signedArtifact.invalid() != null) {
                warnings.add(signedArtifact.invalid().details());
            } else {
                signed = exportArtifact(
                        session.tenantId(),
                        "signed-contract-" + session.sessionId(),
                        "signed-contract.yaml",
                        "application/yaml",
                        signedArtifact.bytes());
            }
        }
        StudioExportArtifact contract = exportArtifact(
                session.tenantId(),
                "contract-" + session.sessionId(),
                "contract.yaml",
                "application/yaml",
                compiled.contractBytes());
        StudioExportArtifact profile = exportArtifact(
                session.tenantId(),
                "substrate-profile-" + session.sessionId(),
                "substrate-profile.yaml",
                "application/yaml",
                compiled.profileBytes());
        StudioCompileDraftCandidateResponse response = new StudioCompileDraftCandidateResponse(
                "COMPILED",
                build.candidate().candidateId(),
                contract,
                profile,
                signed,
                compileWarnings(session, warnings),
                "",
                "",
                session.sceneRevision(),
                expected);
        return new StudioCompileDraftCandidateResponse(
                response.status(),
                response.candidateId(),
                response.contractArtifact(),
                response.substrateProfileArtifact(),
                response.signedContractArtifact(),
                response.warnings(),
                response.reason(),
                response.details(),
                response.expectedRevision(),
                response.receivedRevision(),
                List.of(diagnosticArtifact(session.tenantId(), "compile-response", "compile-response.json", response)));
    }

    /**
     * Response factory: creates a typed invalid compile response plus a downloadable
     * diagnostic snapshot that includes the server/caller revision details.
     */
    private StudioCompileDraftCandidateResponse invalidCompileResponse(
            StudioDraftSession session,
            InvalidDraft invalid,
            long receivedRevision
    ) {
        StudioCompileDraftCandidateResponse response = new StudioCompileDraftCandidateResponse(
                "INVALID",
                "",
                null,
                null,
                null,
                List.of(),
                invalid.reason(),
                invalid.details(),
                session.sceneRevision(),
                receivedRevision);
        return new StudioCompileDraftCandidateResponse(
                response.status(),
                response.candidateId(),
                response.contractArtifact(),
                response.substrateProfileArtifact(),
                response.signedContractArtifact(),
                response.warnings(),
                response.reason(),
                response.details(),
                response.expectedRevision(),
                response.receivedRevision(),
                List.of(diagnosticArtifact(session.tenantId(), "compile-invalid", "compile-response.json", response)));
    }

    /**
     * Projector: combines collaboration warnings with candidate/export warnings without
     * losing deterministic ordering.
     */
    private List<String> compileWarnings(StudioDraftSession session, List<String> warnings) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (session.collaborators().size() > 1) {
            out.add("compiled shared session with " + session.collaborators().size() + " active collaborators");
        }
        if (warnings != null) {
            out.addAll(warnings);
        }
        return List.copyOf(out);
    }

    /**
     * Strategy: resolves the effective Need for a session from explicit request YAML,
     * request/session needs id, or a conservative empty need fallback.
     */
    private Need needForSession(StudioDraftSession session, String requestNeedsId, String needsYaml) {
        if (needsYaml != null && !needsYaml.isBlank()) {
            return new NeedsCodec().parse(needsYaml.getBytes(StandardCharsets.UTF_8));
        }
        String needsId = requestNeedsId == null || requestNeedsId.isBlank()
                ? session.needsId()
                : requestNeedsId;
        if (needsId != null && !needsId.isBlank()) {
            Need need = needsByTenant
                    .getOrDefault(session.tenantId(), Map.of())
                    .get(needsId);
            if (need != null) {
                return need;
            }
        }
        return Need.ofRequiredCapabilities();
    }

    /**
     * Builder: replays the session intent log into a full DCP composition candidate.
     * Removed/replaced components are excluded, so compile is no longer tied to the
     * current candidate pointer.
     */
    private CandidateBuild candidateForSession(StudioDraftSession session, Need need) {
        List<StudioVisualCatalogEntry> catalog = entriesByTenant.computeIfAbsent(session.tenantId(), this::fixtureEntries);
        Map<String, StudioVisualCatalogEntry> byId = new LinkedHashMap<>();
        catalog.forEach(entry -> byId.put(entry.catalogEntryId(), entry));
        LinkedHashSet<String> selectedIds = selectedCatalogEntryIds(session);
        if (selectedIds.isEmpty()) {
            return CandidateBuild.invalid(new InvalidDraft("EMPTY_DRAFT", "draft contains no components"));
        }
        List<CatalogEntry> selected = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String entryId : selectedIds) {
            StudioVisualCatalogEntry visual = byId.get(entryId);
            if (visual == null) {
                return CandidateBuild.invalid(new InvalidDraft(
                        "CATALOG_ENTRY_NOT_FOUND",
                        "draft references catalog entry '" + entryId + "' that is not registered in tenant '"
                                + session.tenantId() + "'"));
            }
            selected.add(catalogEntryForVisual(session.tenantId(), visual));
            warnings.addAll(visual.warnings());
        }
        CandidateValidity validity = new CandidateValidator().validate(selected, need);
        if (!validity.isValid()) {
            String details = validity.conflicts().stream()
                    .map(Conflict::detail)
                    .sorted()
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("selected draft inventory does not satisfy the requested needs");
            return CandidateBuild.invalid(new InvalidDraft("NO_MATCH", details));
        }
        CompositionCandidate candidate = new CompositionCandidate(
                CompositionCandidate.computeId(selected),
                selected.stream().sorted(CatalogEntry.CANONICAL_ORDER).toList(),
                satisfiedRequiredCapabilities(selected, need),
                satisfiedOptionalCapabilities(selected, need),
                dependencyBindings(selected),
                List.<PlanningWarning>of(),
                new Scorer().score(selected, need));
        return CandidateBuild.valid(candidate, warnings);
    }

    /**
     * Projector: replays component membership intents into a deterministic catalog-entry id
     * set. This mirrors the TypeScript draft inventory replay used by the visual workspace.
     */
    private LinkedHashSet<String> selectedCatalogEntryIds(StudioDraftSession session) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (StudioIntentRecord intent : session.intentLog()) {
            Map<String, Object> payload = intent.payload();
            if ("ADD_COMPONENT".equals(intent.type())) {
                addIfPresent(selected, stringValue(payload.get("catalogEntryId"), ""));
            }
            if ("REMOVE_COMPONENT".equals(intent.type())) {
                String catalogEntryId = stringValue(payload.get("catalogEntryId"), "");
                String componentId = stringValue(payload.get("componentId"), "");
                selected.remove(catalogEntryId.isBlank() ? catalogEntryIdFromComponentId(componentId) : catalogEntryId);
            }
            if ("REPLACE_COMPONENT".equals(intent.type())) {
                String oldCatalogEntryId = stringValue(payload.get("oldCatalogEntryId"), "");
                String oldComponentId = stringValue(payload.get("oldComponentId"), "");
                selected.remove(oldCatalogEntryId.isBlank()
                        ? catalogEntryIdFromComponentId(oldComponentId)
                        : oldCatalogEntryId);
                addIfPresent(selected, stringValue(payload.get("newCatalogEntryId"), ""));
            }
        }
        return selected;
    }

    /**
     * Set mutator: adds a non-blank value while preserving insertion order.
     */
    private void addIfPresent(LinkedHashSet<String> selected, String value) {
        if (value != null && !value.isBlank()) {
            selected.add(value);
        }
    }

    /**
     * Identity parser: converts pending draft component ids back into catalog entry ids.
     */
    private String catalogEntryIdFromComponentId(String componentId) {
        String value = componentId == null ? "" : componentId.trim();
        return value.startsWith("draft:") ? value.substring("draft:".length()) : value;
    }

    /**
     * Adapter: returns the runtime catalog entry backing a visual catalog item, reconstructing
     * a DCP-only entry when the visual came from legacy visual assets.
     */
    private CatalogEntry catalogEntryForVisual(String tenantId, StudioVisualCatalogEntry entry) {
        String tenant = normalizeTenant(tenantId);
        CatalogEntry known = catalogEntriesByTenant
                .getOrDefault(tenant, Map.of())
                .get(entry.catalogEntryId());
        if (known != null) {
            return known;
        }
        Claim claim = claimForEntry(entry);
        ArtifactDescriptor artifact = new ArtifactDescriptor(
                fallbackArtifactCoordinates(entry.catalogEntryId(), claim),
                "studio-visual",
                "studio-catalog",
                artifactDescriptorSha(entry.artifactSha256()),
                null);
        CatalogEntry reconstructed = new CatalogEntry(
                artifact,
                new ClaimDescriptor(claim, artifactDescriptorSha(entry.claimHash())),
                new CatalogMetadata(Lifecycle.active(), BindingDescriptor.inProcessOnly()),
                shapeProfileFromVisual(entry),
                null);
        catalogEntriesByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(entry.catalogEntryId(), reconstructed);
        return reconstructed;
    }

    /**
     * Projector: infers a component shape profile from visual fallback metadata when no
     * parsed catalog manifest is available.
     */
    private ComponentShapeProfile shapeProfileFromVisual(StudioVisualCatalogEntry entry) {
        Object fallback = entry.visualManifest().get("fallbackShape");
        if (!(fallback instanceof Map<?, ?> map)) {
            return null;
        }
        String category = stringValue(map.get("category"), "");
        try {
            DeploymentShape shape = DeploymentShape.valueOf(category);
            return new ComponentShapeProfile(shape, Set.of(shape), Map.of());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Calculator: records which required capabilities the selected entries satisfy.
     */
    private Set<String> satisfiedRequiredCapabilities(List<CatalogEntry> entries, Need need) {
        return satisfiedCapabilities(entries, need.requiredCapabilities());
    }

    /**
     * Calculator: records which optional capabilities the selected entries satisfy.
     */
    private Set<String> satisfiedOptionalCapabilities(List<CatalogEntry> entries, Need need) {
        return satisfiedCapabilities(entries, need.optionalCapabilities());
    }

    /**
     * Calculator helper: computes satisfied capability names for a requirement list.
     */
    private Set<String> satisfiedCapabilities(List<CatalogEntry> entries, List<CapabilityRequirement> requirements) {
        LinkedHashSet<String> satisfied = new LinkedHashSet<>();
        for (CapabilityRequirement requirement : requirements) {
            for (CatalogEntry entry : entries) {
                for (Offer offer : entry.claimDescriptor().claim().offers()) {
                    if (requirement.capability().equals(offer.capability())
                            && requirement.capabilityVersion().satisfiedBy(offer.version())) {
                        satisfied.add(requirement.capability());
                    }
                }
            }
        }
        return Set.copyOf(satisfied);
    }

    /**
     * Projector: records component-to-component and host-bound dependency bindings for the
     * selected entry set so the compiled audit has a truthful draft dependency view.
     */
    private List<DependencyBinding> dependencyBindings(List<CatalogEntry> entries) {
        Map<String, String> providersByCapability = new LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            for (Offer offer : entry.claimDescriptor().claim().offers()) {
                providersByCapability.putIfAbsent(offer.capability(), entry.artifact().coordinates());
            }
        }
        List<DependencyBinding> bindings = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            if (entry.claimDescriptor().claim().dependencies() == null) {
                continue;
            }
            for (String dep : entry.claimDescriptor().claim().dependencies().needs()) {
                if (dep == null || dep.isBlank()) {
                    continue;
                }
                String capability = capabilityNameFromDependencyUri(dep);
                String provider = capability == null ? null : providersByCapability.get(capability);
                boolean hostBound = provider == null
                        && (dep.contains("substrate=true") || dep.contains("owner=customer-controlled"));
                bindings.add(new DependencyBinding(dep, provider, hostBound));
            }
        }
        return bindings;
    }

    /**
     * Compiler adapter: mirrors the CLI compile/profile pipeline using an already resolved
     * deployment binding plan.
     */
    private CompiledArtifacts compileArtifacts(
            CompositionCandidate candidate,
            Need need,
            ResolverOutcome resolved
    ) {
        CompiledContract compiled = new ContractCompiler()
                .compile(candidate, need, new HostOwnerMeta(null, null, null));
        BindingPlan bindingPlan = resolved.plan();
        SubstrateProfile profile = new SubstrateProfileDeriver().derive(candidate, bindingPlan);
        SubstrateProfileCodec profileCodec = new SubstrateProfileCodec();
        SubstrateProfile hashedProfile = profile.withProfileHash(profileCodec.computeProfileHash(profile));
        CompiledContract withProfileHash = new CompiledContract(
                compiled.contract(),
                selectionsWithDeploymentShapes(compiled.selections(), bindingPlan),
                compiled.audit(),
                hashedProfile.profileHash(),
                bindingPlan,
                compiled.signature());
        return new CompiledArtifacts(
                withProfileHash,
                new CompiledContractCodec().write(withProfileHash),
                profileCodec.write(hashedProfile));
    }

    /**
     * Projector: annotates compile selections with deployment shapes from the binding plan.
     */
    private List<SelectionRecord> selectionsWithDeploymentShapes(
            List<SelectionRecord> selections,
            BindingPlan bindingPlan
    ) {
        Map<String, DeploymentShape> shapesByCoordinates = bindingPlan.entries().stream()
                .collect(java.util.stream.Collectors.toMap(
                        BindingPlanEntry::artifactCoordinates,
                        BindingPlanEntry::deploymentShape,
                        (left, right) -> left,
                        LinkedHashMap::new));
        return selections.stream()
                .map(selection -> new SelectionRecord(
                        selection.artifact(),
                        selection.claimHash(),
                        selection.bindingMode(),
                        selection.chosenInterfaceKind(),
                        shapesByCoordinates.get(selection.artifact().coordinates())))
                .toList();
    }

    /**
     * Signing adapter: signs a compiled contract only when Studio has an operator key pair
     * configured via properties or environment variables.
     */
    private SignedArtifact signCompiledContract(CompiledContract contract) {
        Optional<FabricContractSigner> signer = studioSigner();
        if (signer.isEmpty()) {
            return SignedArtifact.invalid(new InvalidDraft(
                    "SIGNING_KEY_REQUIRED",
                    "signing requested but Studio signing keys are not configured"));
        }
        try {
            SignedFabricContract signed = signer.get().signCompiledContract(contract);
            return SignedArtifact.valid(new SignedFabricContractCodec().write(signed));
        } catch (RuntimeException ex) {
            return SignedArtifact.invalid(new InvalidDraft("SIGNING_FAILED", ex.getMessage()));
        }
    }

    /**
     * Factory: loads the configured Studio signing key pair without hardcoding a key into
     * Fabric. Properties win over environment variables for local test control.
     */
    private Optional<FabricContractSigner> studioSigner() {
        String privateKey = configValue("unfurl.studio.signing.privateKey", "UNFURL_STUDIO_SIGNING_PRIVATE_KEY");
        String publicKey = configValue("unfurl.studio.signing.publicKey", "UNFURL_STUDIO_SIGNING_PUBLIC_KEY");
        if (privateKey.isBlank() || publicKey.isBlank()) {
            return Optional.empty();
        }
        try {
            SigningKeyLoader.LoadedPrivateKey loadedPrivate = SigningKeyLoader.loadPrivateKey(Path.of(privateKey));
            SigningKeyLoader.LoadedPublicKey loadedPublic = SigningKeyLoader.loadPublicKey(Path.of(publicKey));
            return Optional.of(new FabricContractSigner(
                    loadedPrivate.key(),
                    loadedPrivate.signatureAlgorithm(),
                    loadedPublic.fingerprint()));
        } catch (FabricSigningException ex) {
            throw ex;
        }
    }

    /**
     * Configuration helper: reads a property/environment pair and normalizes absent values.
     */
    private String configValue(String property, String env) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null ? "" : value.trim();
    }

    public StudioVisualAsset visualAsset(String tenantId, String assetId) {
        String tenant = normalizeTenant(tenantId);
        String normalizedAssetId = assetId == null ? "" : assetId.trim();
        if (normalizedAssetId.isBlank()) {
            throw new IllegalArgumentException("asset id is required");
        }
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        for (StudioVisualCatalogEntry entry : entries) {
            for (Map<String, Object> asset : visualAssets(entry)) {
                if (normalizedAssetId.equals(stringValue(asset.get("assetId"), ""))) {
                    String sha = stringValue(asset.get("sha256"), "");
                    return new StudioVisualAsset(
                            normalizedAssetId,
                            stringValue(asset.get("path"), ""),
                            stringValue(asset.get("mediaType"), mediaTypeForPath(stringValue(asset.get("path"), ""))),
                            sha,
                            "/studio/tenants/" + tenant + "/assets/" + normalizedAssetId + "/content?sha256=" + sha,
                            sha.startsWith("sha256:") ? "HASH_PINNED" : "FALLBACK_REQUIRED",
                            sha.startsWith("sha256:") ? "" : "asset hash missing; Studio must render generated fallback shape");
                }
            }
        }
        return new StudioVisualAsset(
                normalizedAssetId,
                "",
                "application/octet-stream",
                "",
                "",
                "FALLBACK_REQUIRED",
                "asset is not present in the tenant visual catalog");
    }

    public Optional<StudioAssetContent> visualAssetContent(String tenantId, String assetId, String requestedSha256) {
        StudioVisualAsset asset = visualAsset(tenantId, assetId);
        if (!"HASH_PINNED".equals(asset.status()) || assetRoot == null || asset.path().isBlank()) {
            return Optional.empty();
        }
        if (requestedSha256 != null && !requestedSha256.isBlank() && !asset.sha256().equals(requestedSha256)) {
            return Optional.empty();
        }
        Path target = assetRoot.resolve(asset.path()).normalize();
        if (!target.startsWith(assetRoot) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String actual = sha256(bytes);
            if (!asset.sha256().equals(actual)) {
                return Optional.empty();
            }
            return Optional.of(new StudioAssetContent(bytes, asset.mediaType(), actual));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Download accessor: returns a previously generated catalog-admission claim bundle only
     * when the caller supplies the matching hash pin.
     */
    public Optional<StudioAssetContent> claimBundleContent(String tenantId, String admissionId, String requestedSha256) {
        String tenant = normalizeTenant(tenantId);
        String normalizedAdmissionId = admissionId == null ? "" : admissionId.trim();
        if (normalizedAdmissionId.isBlank()) {
            return Optional.empty();
        }
        StudioAssetContent content = claimBundlesByTenant
                .getOrDefault(tenant, Map.of())
                .get(normalizedAdmissionId);
        if (content == null) {
            return Optional.empty();
        }
        if (requestedSha256 != null && !requestedSha256.isBlank() && !content.sha256().equals(requestedSha256)) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    /**
     * Download accessor: returns a generated diagnostic artifact only when the caller
     * supplies the matching hash pin.
     */
    public Optional<StudioAssetContent> diagnosticArtifactContent(String tenantId, String artifactId, String requestedSha256) {
        String tenant = normalizeTenant(tenantId);
        String normalizedArtifactId = artifactId == null ? "" : artifactId.trim();
        if (normalizedArtifactId.isBlank()) {
            return Optional.empty();
        }
        StudioAssetContent content = diagnosticArtifactsByTenant
                .getOrDefault(tenant, Map.of())
                .get(normalizedArtifactId);
        if (content == null) {
            return Optional.empty();
        }
        if (requestedSha256 != null && !requestedSha256.isBlank() && !content.sha256().equals(requestedSha256)) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    /**
     * Download accessor: returns a generated compile/export artifact only when the caller
     * supplies the matching hash pin.
     */
    public Optional<StudioAssetContent> exportArtifactContent(String tenantId, String artifactId, String requestedSha256) {
        String tenant = normalizeTenant(tenantId);
        String normalizedArtifactId = artifactId == null ? "" : artifactId.trim();
        if (normalizedArtifactId.isBlank()) {
            return Optional.empty();
        }
        StudioAssetContent content = exportArtifactsByTenant
                .getOrDefault(tenant, Map.of())
                .get(normalizedArtifactId);
        if (content == null) {
            return Optional.empty();
        }
        if (requestedSha256 != null && !requestedSha256.isBlank() && !content.sha256().equals(requestedSha256)) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    private StudioCatalogVisualsResponse response(List<StudioVisualCatalogEntry> entries) {
        return new StudioCatalogVisualsResponse(
                sha256(entries.stream().map(StudioVisualCatalogEntry::catalogEntryId).sorted().toList().toString()),
                entries);
    }

    /**
     * Factory: stores an immutable JSON diagnostic snapshot and returns the hash-pinned
     * artifact metadata used by Studio download buttons.
     */
    private StudioExportArtifact diagnosticArtifact(String tenantId, String kind, String fileName, Object body) {
        try {
            String tenant = normalizeTenant(tenantId);
            String artifactId = kind + "-" + UUID.randomUUID();
            byte[] bytes = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
            String sha = sha256(bytes);
            diagnosticArtifactsByTenant
                    .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                    .put(artifactId, new StudioAssetContent(bytes, "application/json", sha));
            return new StudioExportArtifact(
                    artifactId,
                    "application/json",
                    sha,
                    "/studio/tenants/" + tenant + "/diagnostic-artifacts/" + artifactId
                            + "/content?sha256=" + sha + "&fileName=" + fileName);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to create diagnostic artifact", ex);
        }
    }

    private void persist() {
        if (store == null) {
            return;
        }
        store.save(new StudioStateStore.State(
                Map.copyOf(entriesByTenant),
                Map.copyOf(assembliesByTenant),
                Map.copyOf(layoutsByTenant),
                Map.copyOf(sessionsByTenant)));
    }

    /**
     * Catalog bootstrapper: loads a tenant's initial visual catalog and records any
     * real scanned manifest entries for compile/deployment reuse.
     *
     * <p>Fall-through order: real `META-INF/unfurl-catalog.yaml` JAR entries,
     * package visual assets, then hardcoded fixtures.
     */
    private List<StudioVisualCatalogEntry> fixtureEntries(String tenantId) {
        List<StudioVisualCatalogEntry> manifestEntries = scanCatalogManifests(tenantId);
        List<StudioVisualCatalogEntry> packageEntries = packageVisualAssets.scan(assetRoot);
        if (manifestEntries.isEmpty() && packageEntries.isEmpty()) {
            return bundledFixtureEntries(tenantId);
        }
        Map<String, StudioVisualCatalogEntry> entries = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry entry : manifestEntries) {
            entries.put(entry.catalogEntryId(), entry);
        }
        for (StudioVisualCatalogEntry entry : packageEntries) {
            entries.putIfAbsent(entry.catalogEntryId(), entry);
        }
        for (StudioVisualCatalogEntry entry : bundledFixtureEntries(tenantId)) {
            entries.putIfAbsent(entry.catalogEntryId(), entry);
        }
        return List.copyOf(entries.values());
    }

    /**
     * Scans the asset root for portfolio JARs that ship a
     * {@code META-INF/unfurl-catalog.yaml} manifest. Returns the resulting
     * catalog entries projected into Studio's visual catalog shape.
     *
     * <p>Catalog-scanner errors are swallowed and treated as "no entries"
     * so that a malformed JAR cannot bring down the Studio backend; the
     * other fall-through sources still run.
     */
    private List<StudioVisualCatalogEntry> scanCatalogManifests(String tenantId) {
        if (assetRoot == null) {
            System.err.println("[studio] catalog scan: assetRoot is null — no real catalog");
            return List.of();
        }
        if (!Files.isDirectory(assetRoot)) {
            System.err.println("[studio] catalog scan: assetRoot is not a directory: " + assetRoot);
            return List.of();
        }
        try {
            CatalogScanReport report = new CatalogScanner().scan(assetRoot);
            System.err.println("[studio] catalog scan at " + assetRoot
                    + " produced " + report.catalog().entries().size() + " entries, "
                    + report.skippedEntries().size() + " skipped");
            for (var skipped : report.skippedEntries()) {
                System.err.println("[studio] catalog scan skipped " + skipped.jarPath()
                        + ": " + skipped.reason() + " (" + skipped.detail() + ")");
            }
            Map<String, CatalogEntry> runtimeEntries = new ConcurrentHashMap<>();
            report.catalog().entries().forEach(entry -> runtimeEntries.put(entry.artifact().coordinates(), entry));
            catalogEntriesByTenant.put(normalizeTenant(tenantId), runtimeEntries);
            return report.catalog().entries().stream()
                    .map(this::toVisualEntry)
                    .toList();
        } catch (RuntimeException ex) {
            System.err.println("[studio] catalog scan failed for " + assetRoot + ": " + ex);
            ex.printStackTrace();
            return List.of();
        }
    }

    /**
     * Project a fabric {@link CatalogEntry} into the Studio's
     * {@link StudioVisualCatalogEntry} shape. The catalogEntryId is the
     * artifact coordinates (e.g. {@code com.unfurl.flow:unfurl-flow:0.1.0}).
     * The visual palette gets a synthetic shape descriptor derived from
     * the entry's {@code componentShapeProfile.defaultShape} so each real
     * component shows the right shape badge without needing a {@code .glb}.
     */
    private StudioVisualCatalogEntry toVisualEntry(CatalogEntry entry) {
        Claim claim = entry.claimDescriptor().claim();
        List<String> capabilities = claim.offers() == null
                ? List.of()
                : claim.offers().stream().map(Offer::capability).toList();
        List<String> requiredCapabilities = requiredCapabilitiesFromClaim(claim);
        // Preserve the raw needs strings (with ?owner= markers intact) so
        // the projection's port-edge matcher can skip host- / fabric-owned
        // dependencies — those are external and shouldn't render as
        // in-scene pipes.
        List<String> rawNeeds = claim == null || claim.dependencies() == null || claim.dependencies().needs() == null
                ? List.of()
                : List.copyOf(claim.dependencies().needs());
        String category = entry.optionalComponentShapeProfile()
                .map(profile -> profile.defaultShape().name())
                .orElseGet(() -> claim.identity() == null || claim.identity().kind() == null
                        ? "COMPONENT"
                        : claim.identity().kind().toString());
        String fallbackKind = entry.optionalComponentShapeProfile()
                .map(profile -> fallbackShapeKindFor(profile.defaultShape().name()))
                .orElse("CUBE");
        Map<String, Object> dynamicComposition = dynamicComposition("COMPONENT", List.of(), rawNeeds);
        return new StudioVisualCatalogEntry(
                entry.artifact().coordinates(),
                entry.claimDescriptor().claimHash(),
                entry.artifact().sha256(),
                visual(category, fallbackKind, capabilities, requiredCapabilities),
                dynamicComposition,
                Map.of(),
                List.of());
    }

    /**
     * Parses {@code claim.dependencies.needs} strings (format
     * {@code <cap>[@<version>][?<params>]}) into bare capability names so
     * the visual manifest can emit DEPENDENCY ports alongside its OFFER
     * ports. Mirrors the parser in
     * {@code com.unfurl.fabric.matcher.CandidateValidator.capabilityNameFromDependencyUri}
     * (which is private to that class — duplicating the 8-line trim here
     * is cheaper than widening that class's API for this one caller).
     * Customer-controlled / host-owned dependencies stay in the list
     * because the hover-preview wants to surface them as connection
     * candidates even when fabric's resolver would skip them.
     */
    private List<String> requiredCapabilitiesFromClaim(Claim claim) {
        if (claim == null || claim.dependencies() == null || claim.dependencies().needs() == null) {
            return List.of();
        }
        return claim.dependencies().needs().stream()
                .map(StudioCatalogService::capabilityNameFromDependencyUri)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private static String capabilityNameFromDependencyUri(String dep) {
        if (dep == null) {
            return null;
        }
        String trimmed = dep;
        int q = trimmed.indexOf('?');
        if (q >= 0) {
            trimmed = trimmed.substring(0, q);
        }
        int at = trimmed.indexOf('@');
        if (at >= 0) {
            trimmed = trimmed.substring(0, at);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String queryParam(String dep, String key, String fallback) {
        if (dep == null || key == null || key.isBlank()) {
            return fallback;
        }
        int queryStart = dep.indexOf('?');
        if (queryStart < 0 || queryStart == dep.length() - 1) {
            return fallback;
        }
        String prefix = key + "=";
        for (String pair : dep.substring(queryStart + 1).split("&")) {
            if (pair.startsWith(prefix)) {
                String value = pair.substring(prefix.length());
                return value.isBlank() ? fallback : value;
            }
        }
        return fallback;
    }

    private static String substrateLabel(String capability) {
        if (capability == null || capability.isBlank()) {
            return "Substrate Port";
        }
        String[] parts = capability.replace('-', '.').split("\\.");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                words.add(part.substring(0, 1).toUpperCase() + part.substring(1));
            }
        }
        return words.isEmpty() ? capability : String.join(" ", words);
    }

    private List<StudioVisualCatalogEntry> bundledFixtureEntries(String tenantId) {
        return List.of(
                new StudioVisualCatalogEntry(
                        "com.unfurl:validation-service:1.1.0",
                        sha256("claim:" + tenantId + ":validation-service"),
                        sha256("artifact:validation-service"),
                        visual("WORKFLOW", List.of("validate.order", "validate.payment", "validate.inventory")),
                        dynamicComposition("COMPONENT", List.of("component.customer-policy-validator", "component.fraud-validator")),
                        visualIntegrity("validation-service", "META-INF/visual/validation-service.glb"),
                        List.of()),
                new StudioVisualCatalogEntry(
                        "com.unfurl:storage-s3:1.2.0",
                        sha256("claim:" + tenantId + ":storage-s3"),
                        sha256("artifact:storage-s3"),
                        visual("STORAGE", List.of("storage.put")),
                        dynamicComposition("COMPONENT", List.of("component.azure-blob", "component.minio-storage")),
                        visualIntegrity("storage-s3", "META-INF/visual/storage-s3.glb"),
                        List.of()));
    }

    private Map<String, StudioAssemblySummary> fixtureAssemblies(String tenantId) {
        return new ConcurrentHashMap<>(Map.of("assembly-demo", new StudioAssemblySummary(
                tenantId,
                "assembly-demo",
                "unfurl-flow",
                "Customer Runtime Substrate",
                "",
                "CONTAINERIZED_SERVICE",
                "",
                0)));
    }

    private Map<String, Object> fallbackVisual(String category) {
        return Map.of(
                "fallbackShape", Map.of("kind", "CUBE", "category", category),
                "ports", List.of(),
                "interactions", Map.of("draggable", true, "connectable", true, "inspectable", true));
    }

    private Map<String, Object> visual(String category, List<String> offeredCapabilities) {
        return visual(category, "CUBE", offeredCapabilities);
    }

    /**
     * Build a visual descriptor with a caller-chosen fallback primitive
     * {@code kind}. The renderer supports five kinds: CUBE, SPHERE,
     * CYLINDER, SHIELD, GEAR (see {@code three-renderer/index.ts}).
     * Picking a kind that reflects the component's deployment shape gives
     * each real-catalog entry a visually distinct badge without needing
     * a hand-authored {@code .glb} model.
     */
    private Map<String, Object> visual(String category, String fallbackKind, List<String> offeredCapabilities) {
        return visual(category, fallbackKind, offeredCapabilities, List.of());
    }

    /**
     * Build a visual descriptor that emits one port per offered AND one
     * per required capability. OFFER ports anchor to the right ({@code
     * SQUARE_SOCKET}); DEPENDENCY ports anchor to the left ({@code
     * SQUARE_PLUG}) so the renderer naturally separates "what I provide"
     * from "what I need" along opposite faces of the component group.
     * Both lists feed the hover-preview compatibility matcher in
     * {@link #connectionCandidates}.
     */
    private Map<String, Object> visual(
            String category,
            String fallbackKind,
            List<String> offeredCapabilities,
            List<String> requiredCapabilities
    ) {
        List<Map<String, Object>> ports = new ArrayList<>();
        for (String capability : offeredCapabilities) {
            ports.add(Map.of(
                    "id", capability.replace('.', '-'),
                    "mapsTo", "claim.offers." + capability,
                    "kind", "OFFER",
                    "anchor", "right",
                    "connectorShape", "SQUARE_SOCKET"));
        }
        for (String capability : requiredCapabilities) {
            ports.add(Map.of(
                    "id", "need-" + capability.replace('.', '-'),
                    "mapsTo", "claim.dependencies." + capability,
                    "kind", "DEPENDENCY",
                    "anchor", "left",
                    "connectorShape", "SQUARE_PLUG"));
        }
        return Map.of(
                "fallbackShape", Map.of("kind", fallbackKind, "category", category),
                "ports", List.copyOf(ports),
                "interactions", Map.of("draggable", true, "connectable", true, "inspectable", true));
    }

    /**
     * Map a {@code componentShapeProfile.defaultShape} to one of the
     * renderer's supported primitive kinds. The mapping favours visual
     * distinction over fidelity:
     * <ul>
     *   <li>{@code IN_PROCESS_LIBRARY}, {@code MODULAR_MONOLITH_MODULE} → CUBE</li>
     *   <li>{@code STANDALONE_JAVA_APP}, {@code SPRING_BOOT_SERVICE} → CYLINDER</li>
     *   <li>{@code REMOTE_MICROSERVICE} → SPHERE</li>
     *   <li>{@code CONTAINERIZED_SERVICE} → GEAR</li>
     *   <li>{@code MANAGED_EXTERNAL_ADAPTER} → SHIELD</li>
     * </ul>
     */
    private static String fallbackShapeKindFor(String deploymentShapeName) {
        if (deploymentShapeName == null) {
            return "CUBE";
        }
        return switch (deploymentShapeName) {
            case "IN_PROCESS_LIBRARY", "MODULAR_MONOLITH_MODULE" -> "CUBE";
            case "STANDALONE_JAVA_APP", "SPRING_BOOT_SERVICE" -> "CYLINDER";
            case "REMOTE_MICROSERVICE" -> "SPHERE";
            case "CONTAINERIZED_SERVICE" -> "GEAR";
            case "MANAGED_EXTERNAL_ADAPTER" -> "SHIELD";
            default -> "CUBE";
        };
    }

    /**
     * Builder: creates Studio's dynamic-composition metadata block while preserving raw
     * DCP dependency strings for later candidate reconstruction.
     */
    private Map<String, Object> dynamicComposition(String dcpType, List<String> compatibleDescendants) {
        return dynamicComposition(dcpType, compatibleDescendants, List.of());
    }

    /**
     * Builder: creates Studio's dynamic-composition metadata block with optional raw
     * dependency strings from catalog manifests or admitted claims.
     */
    private Map<String, Object> dynamicComposition(
            String dcpType,
            List<String> compatibleDescendants,
            List<String> rawNeeds
    ) {
        Map<String, Object> dynamic = new LinkedHashMap<>();
        dynamic.put("compositionMode", "DYNAMIC");
        dynamic.put("dcpType", dcpType);
        dynamic.put("compatibleDescendants", compatibleDescendants == null ? List.of() : List.copyOf(compatibleDescendants));
        dynamic.put("selectionPolicy", Map.of("strategy", "POLICY_DRIVEN", "rules", List.of()));
        dynamic.put("binding", Map.of("mode", "LATE_BOUND", "validation", "REQUIRED_BEFORE_ACTIVATION"));
        if (rawNeeds != null && !rawNeeds.isEmpty()) {
            dynamic.put("rawNeeds", List.copyOf(rawNeeds));
        }
        return Map.copyOf(dynamic);
    }

    private Map<String, Object> visualIntegrity(String slug, String path) {
        String thumbnailPath = path.replace(".glb", "-thumbnail.png");
        return Map.of(
                "visualManifestHash", sha256("visual:" + slug),
                "assets", List.of(
                        Map.of(
                                "assetId", slug + "-model",
                                "path", path,
                                "mediaType", mediaTypeForPath(path),
                                "sha256", sha256("asset:" + slug + ":" + path)),
                        Map.of(
                                "assetId", slug + "-thumbnail",
                                "path", thumbnailPath,
                                "mediaType", mediaTypeForPath(thumbnailPath),
                                "sha256", sha256("asset:" + slug + ":" + thumbnailPath))));
    }

    private List<Map<String, Object>> visualAssets(StudioVisualCatalogEntry entry) {
        Object assets = entry.visualIntegrity().get("assets");
        if (!(assets instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private String mediaTypeForPath(String path) {
        String normalized = path == null ? "" : path.toLowerCase();
        if (normalized.endsWith(".glb")) {
            return "model/gltf-binary";
        }
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private StudioDynamicDcpNode dynamicNodeForEntry(StudioVisualCatalogEntry entry) {
        String nodeId = nodeIdForEntry(entry.catalogEntryId());
        Map<String, Object> dynamic = entry.dynamicComposition();
        return new StudioDynamicDcpNode(
                nodeId,
                labelForCatalogEntry(entry.catalogEntryId()),
                stringValue(dynamic.get("dcpType"), "COMPONENT"),
                "CHILD",
                entry.catalogEntryId(),
                capabilitiesFromVisual(entry.visualManifest()),
                stringList(dynamic.get("compatibleDescendants")),
                true);
    }

    private List<String> capabilitiesFromVisual(Map<String, Object> visualManifest) {
        Object ports = visualManifest.get("ports");
        if (!(ports instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(port -> "OFFER".equals(stringValue(port.get("kind"), "")))
                .map(port -> stringValue(port.get("mapsTo"), ""))
                .filter(value -> value.startsWith("claim.offers."))
                .map(value -> value.substring("claim.offers.".length()))
                .sorted()
                .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(item -> !item.isBlank())
                .sorted()
                .toList();
    }

    private String stringValue(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private String nodeIdForEntry(String catalogEntryId) {
        String[] parts = catalogEntryId.split(":");
        String artifact = parts.length >= 2 ? parts[1] : catalogEntryId;
        return "component." + slug(artifact);
    }

    private String labelForCatalogEntry(String catalogEntryId) {
        String[] parts = catalogEntryId.split(":");
        String artifact = parts.length >= 2 ? parts[1] : catalogEntryId;
        return labelFromNodeId("component." + artifact);
    }

    private StudioReplacementCandidate candidateForDescendant(String descendantNodeId, StudioDynamicDcpNode selected) {
        return switch (descendantNodeId) {
            case "component.customer-policy-validator" -> new StudioReplacementCandidate(
                    "com.unfurl:customer-policy-validator:1.2.0",
                    "Customer Policy Validator",
                    "risk-team",
                    "CONTAINER",
                    "ALLOWED",
                    "offers the validation capabilities required by " + selected.label());
            case "component.fraud-validator" -> new StudioReplacementCandidate(
                    "com.unfurl:fraud-only-validator:1.0.0",
                    "Fraud-only Validator",
                    "risk-team",
                    "SERVICE",
                    "BLOCKED",
                    "missing validate.inventory required by the selected DCP slot");
            case "component.azure-blob" -> new StudioReplacementCandidate(
                    "com.unfurl:storage-adapter-blob:1.2.0",
                    "Azure Blob Adapter",
                    "platform-team",
                    "SIDECAR",
                    "ALLOWED",
                    "compatible object-storage capability range for this deployment target");
            case "component.minio-storage" -> new StudioReplacementCandidate(
                    "com.unfurl:storage-adapter-minio:1.1.0",
                    "MinIO Storage Adapter",
                    "platform-team",
                    "SIDECAR",
                    "ALLOWED",
                    "keeps storage inside the tenant perimeter");
            default -> new StudioReplacementCandidate(
                    "dynamic:" + descendantNodeId,
                    labelFromNodeId(descendantNodeId),
                    "fabric",
                    "COMPONENT",
                    "ALLOWED",
                    "declared as a compatible descendant by the dynamic DCP projection");
        };
    }

    private String labelFromNodeId(String nodeId) {
        String value = nodeId == null ? "" : nodeId;
        int separator = value.indexOf('.');
        String slug = separator >= 0 ? value.substring(separator + 1) : value;
        String[] parts = slug.split("-");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                words.add(part.substring(0, 1).toUpperCase() + part.substring(1));
            }
        }
        return words.isEmpty() ? "Dynamic Component" : String.join(" ", words);
    }

    private StudioDraftSession authoringSession(String tenantId, String assemblyId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                return draftSession(tenantId, assemblyId, sessionId);
            } catch (IllegalArgumentException ignored) {
                // Fall through to a fresh governed draft session; the response
                // carries the canonical session id back to the UI.
            }
        }
        return createDraftSession(
                tenantId,
                assemblyId,
                new StudioCreateDraftCompositionRequest(
                        tenantId,
                        assemblyId,
                        response(entriesByTenant.computeIfAbsent(normalizeTenant(tenantId), this::fixtureEntries)).catalogHash(),
                        "",
                        "",
                        "",
                        "authoring-agent",
                        "Authoring Agent")).session();
    }

    private boolean promptMentionsEntry(String prompt, StudioVisualCatalogEntry entry) {
        String lowered = prompt == null ? "" : prompt.toLowerCase();
        String id = entry.catalogEntryId().toLowerCase();
        if (lowered.contains(id)) {
            return true;
        }
        String label = labelForCatalogEntry(entry.catalogEntryId()).toLowerCase();
        if (lowered.contains(label.toLowerCase())) {
            return true;
        }
        return capabilitiesFromVisual(entry.visualManifest()).stream()
                .anyMatch(capability -> lowered.contains(capability.toLowerCase()));
    }

    private Optional<String> firstOfferedCapability(StudioVisualCatalogEntry entry) {
        return capabilitiesFromVisual(entry.visualManifest()).stream().findFirst();
    }

    private String authoringTargetName(String prompt) {
        String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isBlank()) {
            return "this application";
        }
        String withoutCommand = trimmed.replaceFirst("(?i)^(build|make|create|author|compose)\\s+(an?\\s+)?", "");
        if (withoutCommand.length() > 72) {
            withoutCommand = withoutCommand.substring(0, 72).trim();
        }
        return withoutCommand.isBlank() ? "this application" : withoutCommand;
    }

    private String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "tenant-local";
        }
        return tenantId.trim();
    }

    private String normalizeAssembly(String assemblyId) {
        return assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
    }

    /**
     * Factory: creates a minimal assembly summary for snapshot operations when a
     * workspace has layout/session state but no saved draft summary yet.
     */
    private StudioAssemblySummary defaultAssemblySummary(String tenantId, String assemblyId) {
        String assembly = normalizeAssembly(assemblyId);
        return new StudioAssemblySummary(
                normalizeTenant(tenantId),
                assembly,
                assembly,
                "",
                "",
                "CONTAINERIZED_SERVICE",
                "",
                0);
    }

    private String sessionKey(String assemblyId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session id is required");
        }
        return normalizeAssembly(assemblyId) + "/" + sessionId;
    }

    private void putSession(StudioDraftSession session) {
        sessionsByTenant
                .computeIfAbsent(session.tenantId(), ignored -> new ConcurrentHashMap<>())
                .put(sessionKey(session.assemblyId(), session.sessionId()), session);
    }

    private String sessionEventKey(String tenantId, String assemblyId, String sessionId) {
        return normalizeTenant(tenantId) + "/" + normalizeAssembly(assemblyId) + "/" + sessionId;
    }

    private void publishSessionEvent(StudioDraftSession session) {
        StudioSessionEvent event = eventForSession(session);
        eventBus.publish(sessionEventKey(session.tenantId(), session.assemblyId(), session.sessionId()), event);
    }

    private StudioDraftSession pruneCollaborators(StudioDraftSession session) {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(90));
        List<StudioCollaborator> active = session.collaborators().stream()
                .filter(collaborator -> collaborator.lastSeenAt().isAfter(cutoff))
                .sorted(Comparator.comparing(StudioCollaborator::collaboratorId))
                .toList();
        if (active.size() == session.collaborators().size()) {
            return session;
        }
        StudioDraftSession pruned = new StudioDraftSession(
                session.tenantId(),
                session.assemblyId(),
                session.sessionId(),
                session.baseCatalogHash(),
                session.compositionMode(),
                session.needsId(),
                session.trustPolicyId(),
                session.currentCandidateId(),
                session.sceneRevision(),
                session.warnings(),
                active,
                session.intentLog());
        putSession(pruned);
        persist();
        return pruned;
    }

    private List<StudioCollaborator> upsertCollaborator(
            List<StudioCollaborator> collaborators,
            String collaboratorId,
            String displayName,
            String selectedSurface
    ) {
        String id = collaboratorId(collaboratorId);
        List<StudioCollaborator> updated = new ArrayList<>(collaborators == null ? List.of() : collaborators);
        updated.removeIf(existing -> existing.collaboratorId().equals(id));
        updated.add(collaborator(id, displayName, selectedSurface));
        return updated.stream()
                .sorted(Comparator.comparing(StudioCollaborator::collaboratorId))
                .toList();
    }

    private StudioCollaborator collaborator(String collaboratorId, String displayName, String selectedSurface) {
        String id = collaboratorId(collaboratorId);
        return new StudioCollaborator(
                id,
                displayName == null || displayName.isBlank() ? id : displayName,
                selectedSurface,
                Instant.now());
    }

    private String collaboratorId(String collaboratorId) {
        return collaboratorId == null || collaboratorId.isBlank() ? "anonymous" : collaboratorId.trim();
    }

    /**
     * Projector: derives the session's current candidate pointer after a validated Studio intent.
     * ADD/REPLACE point at the accepted catalog entry, REMOVE clears the pointer when the removed
     * catalog entry was the active candidate, and non-component intents preserve the existing
     * candidate so compile/export continues from the latest governed component selection.
     */
    private String candidateAfterIntent(String currentCandidateId, StudioIntentRequest request, Map<String, Object> payload) {
        if ("REPLACE_COMPONENT".equals(request.type)) {
            return stringValue(payload.get("newCatalogEntryId"), currentCandidateId);
        }
        if ("ADD_COMPONENT".equals(request.type)) {
            return stringValue(payload.get("catalogEntryId"), currentCandidateId);
        }
        if ("REMOVE_COMPONENT".equals(request.type)
                && currentCandidateId != null
                && currentCandidateId.equals(stringValue(payload.get("catalogEntryId"), ""))) {
            return "";
        }
        return currentCandidateId == null ? "" : currentCandidateId;
    }

    /**
     * Reject intents that reference a catalog entry id not present in the
     * tenant's catalog. Returns {@code Optional.empty()} when the intent is
     * acceptable to proceed; otherwise an {@code invalid} response naming
     * the offending entry id.
     *
     * <p>Validation surface: {@code ADD_COMPONENT} (payload key
     * {@code catalogEntryId}) and {@code REPLACE_COMPONENT} (payload key
     * {@code newCatalogEntryId}). Other intent types are not gated against
     * the catalog.
     */
    private Optional<StudioIntentResponse> rejectIntentAgainstCatalog(
            String tenantId, String intentType, Map<String, Object> payload) {
        String key;
        if ("ADD_COMPONENT".equals(intentType)) {
            key = "catalogEntryId";
        } else if ("REPLACE_COMPONENT".equals(intentType)) {
            key = "newCatalogEntryId";
        } else {
            return Optional.empty();
        }
        String requestedEntryId = stringValue(payload.get(key), "");
        if (requestedEntryId.isBlank()) {
            return Optional.of(StudioIntentResponse.invalid(
                    "CATALOG_ENTRY_REQUIRED",
                    intentType + " requires a non-blank " + key));
        }
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries =
                entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        boolean known = entries.stream()
                .anyMatch(entry -> requestedEntryId.equals(entry.catalogEntryId()));
        if (!known) {
            return Optional.of(StudioIntentResponse.invalid(
                    "CATALOG_ENTRY_NOT_FOUND",
                    "catalog entry '" + requestedEntryId + "' is not registered in tenant '" + tenant + "'"));
        }
        return Optional.empty();
    }

    private void updateAssemblyRevision(StudioDraftSession session) {
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(session.tenantId(), this::fixtureAssemblies);
        StudioAssemblySummary previous = assemblies.getOrDefault(session.assemblyId(), fixtureAssemblies(session.tenantId()).get("assembly-demo"));
        assemblies.put(session.assemblyId(), new StudioAssemblySummary(
                session.tenantId(),
                session.assemblyId(),
                previous == null ? "" : previous.targetApplicationName(),
                previous == null ? "" : previous.defaultDeploymentTarget(),
                session.needsId(),
                previous == null ? "CONTAINERIZED_SERVICE" : previous.deploymentShape(),
                session.currentCandidateId(),
                Math.toIntExact(session.sceneRevision())));
    }

    /**
     * Factory: stores an immutable export artifact and returns the tenant-scoped,
     * hash-pinned URL clients should use to download it.
     */
    private StudioExportArtifact exportArtifact(
            String tenantId,
            String artifactId,
            String fileName,
            String mediaType,
            byte[] bytes
    ) {
        String tenant = normalizeTenant(tenantId);
        String sha = sha256(bytes);
        exportArtifactsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(artifactId, new StudioAssetContent(bytes, mediaType, sha));
        return new StudioExportArtifact(
                artifactId,
                mediaType,
                sha,
                "/studio/tenants/" + tenant + "/exports/" + artifactId
                        + "/content?sha256=" + sha + "&fileName=" + fileName);
    }

    private static Path defaultAssetRoot() {
        String configured = System.getProperty("unfurl.studio.asset.root");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("UNFURL_STUDIO_ASSET_ROOT");
        }
        return configured == null || configured.isBlank() ? StudioFixtureAssets.assetRoot() : Path.of(configured);
    }

    private String slug(String value) {
        String slug = value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "local" : slug;
    }

    /**
     * YAML scalar helper: quotes manifest values generated from user-controlled file names
     * and catalog ids so the admission manifest remains parseable.
     */
    private String yamlQuote(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Value object: result of replaying a draft session into a compile-ready
     * composition candidate, or the invalid draft reason if replay/validation failed.
     */
    private record CandidateBuild(
            CompositionCandidate candidate,
            List<String> warnings,
            InvalidDraft invalid
    ) {
        /**
         * Factory for valid candidate builds.
         */
        static CandidateBuild valid(CompositionCandidate candidate, List<String> warnings) {
            return new CandidateBuild(candidate, warnings == null ? List.of() : List.copyOf(warnings), null);
        }

        /**
         * Factory for invalid candidate builds.
         */
        static CandidateBuild invalid(InvalidDraft invalid) {
            return new CandidateBuild(null, List.of(), invalid);
        }
    }

    /**
     * Value object: compile/export artifact bytes before they are registered in the
     * tenant export store.
     */
    private record CompiledArtifacts(
            CompiledContract contract,
            byte[] contractBytes,
            byte[] profileBytes
    ) {
        /**
         * Invariant constructor: clones mutable byte arrays.
         */
        CompiledArtifacts {
            contractBytes = contractBytes == null ? new byte[0] : contractBytes.clone();
            profileBytes = profileBytes == null ? new byte[0] : profileBytes.clone();
        }

        @Override
        public byte[] contractBytes() {
            return contractBytes.clone();
        }

        @Override
        public byte[] profileBytes() {
            return profileBytes.clone();
        }
    }

    /**
     * Value object: signed-contract bytes or a reason explaining why signing was not
     * performed.
     */
    private record SignedArtifact(
            byte[] bytes,
            InvalidDraft invalid
    ) {
        /**
         * Factory for a successfully signed artifact.
         */
        static SignedArtifact valid(byte[] bytes) {
            return new SignedArtifact(bytes == null ? new byte[0] : bytes.clone(), null);
        }

        /**
         * Factory for a failed or unavailable signing attempt.
         */
        static SignedArtifact invalid(InvalidDraft invalid) {
            return new SignedArtifact(new byte[0], invalid);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * Value object: user-facing invalid draft status for compile/resolve responses.
     */
    private record InvalidDraft(
            String reason,
            String details
    ) {
        /**
         * Invariant constructor: normalizes absent text so JSON responses are stable.
         */
        InvalidDraft {
            reason = reason == null || reason.isBlank() ? "INVALID_DRAFT" : reason;
            details = details == null ? "" : details;
        }
    }

    /**
     * Value object: records the resolved YAML that was actually validated for a verified
     * upload so the downloadable claim bundle is faithful to admission.
     */
    private record ResolvedClaimBundleEntry(
            String fileName,
            String catalogEntryId,
            String claimHash,
            String claimYaml
    ) {
        /**
         * Invariant constructor: normalizes nullable admission fields before ZIP emission.
         */
        private ResolvedClaimBundleEntry {
            fileName = fileName == null ? "" : fileName;
            catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
            claimHash = claimHash == null ? "" : claimHash;
            claimYaml = claimYaml == null ? "" : claimYaml;
        }
    }
}
