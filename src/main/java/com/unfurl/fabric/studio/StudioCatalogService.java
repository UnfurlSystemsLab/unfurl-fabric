package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.ClaimMetadata;
import com.unfurl.dcp.claim.ComponentKind;
import com.unfurl.dcp.claim.Concern;
import com.unfurl.dcp.claim.ConflictResolution;
import com.unfurl.dcp.claim.ConsumerAccess;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.DomainAssertion;
import com.unfurl.dcp.claim.Identity;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OfferInterface;
import com.unfurl.dcp.claim.Refusal;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.dcp.projection.DcpProjection;
import com.unfurl.dcp.projection.DcpProjectionEdge;
import com.unfurl.dcp.projection.DcpProjectionNode;
import com.unfurl.dcp.projection.DcpProjectionProjector;
import com.unfurl.dcp.projection.DcpProjectionRequest;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.ContractFreezer;
import com.unfurl.dcp.trust.SigningKeyRef;
import com.unfurl.deployment.domain.ComponentShapeProfile;
import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.artifact.ArtifactDescriptor;
import com.unfurl.fabric.catalog.BindingDescriptor;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogManifestCodec;
import com.unfurl.fabric.catalog.CatalogMetadata;
import com.unfurl.fabric.catalog.CatalogScanException;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;
import com.unfurl.fabric.catalog.ClaimDescriptor;
import com.unfurl.fabric.catalog.Lifecycle;
import com.unfurl.fabric.catalog.ParsedManifest;
import com.unfurl.fabric.compile.ContractCompiler;
import com.unfurl.fabric.compile.HostOwnerMeta;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.CompiledContractCodec;
import com.unfurl.fabric.compiler.CompositionContractCodec;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.graph.StronglyConnectedGroups;
import com.unfurl.fabric.matcher.CandidateValidity;
import com.unfurl.fabric.matcher.CandidateValidator;
import com.unfurl.fabric.matcher.MatchFailureReason;
import com.unfurl.fabric.matcher.NoMatchExplanation;
import com.unfurl.fabric.matcher.CompositionCandidate;
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
import com.unfurl.fabric.signing.SignatureVerificationResult;
import com.unfurl.fabric.signing.SignatureVerifier;
import com.unfurl.fabric.verify.CatalogDriftChecker;
import com.unfurl.fabric.verify.CatalogDriftReport;
import com.unfurl.fabric.verify.ContractDiff;
import com.unfurl.fabric.verify.ContractDiffer;
import com.unfurl.fabric.verify.FabricVerificationReport;
import com.unfurl.fabric.verify.TrustKeyDirectoryLoader;
import com.unfurl.dcp.trust.VerificationKeySet;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    /** An assembly snapshot whose sessions cannot be imported as a whole (LLD-fabric-studio-api, Assembly snapshots). */
    public static final String ASSEMBLY_SNAPSHOT_INVALID = "ASSEMBLY_SNAPSHOT_INVALID";
    /** The decision audit's selection mode for every Studio compile: the operator composed the draft (CLI's MANUAL). */
    private static final String STUDIO_SELECTION_MODE = "MANUAL";
    private static final int AUTHORING_CONTEXT_CONVERSATION_LIMIT = 8;
    private static final int AUTHORING_CONTEXT_CATALOG_FILE_LIMIT = 8;
    private static final int AUTHORING_CONTEXT_SESSION_HISTORY_LIMIT = 8;
    private static final int AUTHORING_CONTEXT_CATALOG_PREVIEW_LIMIT = 12;
    private static final int AUTHORING_CONTEXT_FOLLOWUP_CONVERSATION_LIMIT = 4;
    private static final int AUTHORING_CONTEXT_FOLLOWUP_CATALOG_FILE_LIMIT = 1;
    private static final int AUTHORING_CONTEXT_FOLLOWUP_SESSION_HISTORY_LIMIT = 1;
    private static final int AUTHORING_CONTEXT_FOLLOWUP_CATALOG_PREVIEW_LIMIT = 6;

    private final Map<String, List<StudioVisualCatalogEntry>> entriesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioLayoutState>> layoutsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioDraftSession>> sessionsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioFileRecord>> filesByTenant = new ConcurrentHashMap<>();
    private final Map<String, List<StudioSessionFileLink>> sessionFileLinksByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioCatalogSnapshot>> catalogSnapshotsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssetContent>> claimBundlesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssetContent>> diagnosticArtifactsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssetContent>> exportArtifactsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CatalogEntry>> catalogEntriesByTenant = new ConcurrentHashMap<>();
    /** Admitted companion jars per tenant, by coordinates (HLD-in-process-component-hosting §3); bytes in companionStore. */
    private final Map<String, Map<String, StudioCompanionRecord>> companionsByTenant = new ConcurrentHashMap<>();
    /** The tenants' deployment environments, by name (LLD-fabric-studio-api, Deployment Environments). */
    private final Map<String, Map<String, StudioDeploymentEnvironment>> environmentsByTenant = new ConcurrentHashMap<>();
    /**
     * Runs emit-plan previews in isolated, deleted folders (LLD-fabric-studio-api, StudioEmitPlanPreview), with the
     * emitters the server installs; {@link #useEmitters} replaces the discovery.
     */
    private volatile StudioEmitPlanRunner emitPlanRunner = new StudioEmitPlanRunner();
    /** One lock per session, so at most one preview runs per session at a time. */
    private final Map<String, Object> emitPlanLocks = new ConcurrentHashMap<>();
    /** The last preview answered per session, with the key it was answered for. */
    private final Map<String, CachedEmitPlan> emitPlanCache = new ConcurrentHashMap<>();
    /** Revision-checked access to {@link #environmentsByTenant}, with the save-time check. */
    private final StudioDeploymentEnvironments environments = new StudioDeploymentEnvironments(environmentsByTenant,
            this::persist, java.time.Clock.systemUTC(),
            () -> new com.unfurl.deploy.core.EmitterBootstrap().installedTargetKinds());
    /** The tenant's content-addressed jar store: admitted companions and admitted components' jars. */
    private final StudioCompanionStore companionStore;
    private final Map<String, Map<String, Need>> needsByTenant = new ConcurrentHashMap<>();
    private final StudioStateStore store;
    private final Path assetRoot;
    private final StudioSessionEventBus eventBus;
    private final StudioPackageVisualAssets packageVisualAssets = new StudioPackageVisualAssets();
    private final StudioClaimAdmissionValidator claimAdmissionValidator = new StudioClaimAdmissionValidator();
    private final ObjectMapper jsonMapper = StudioJson.mapper();
    private final StudioDeploymentService deploymentService = new StudioDeploymentService();
    /** The one freshness rule for compile-derived values (LLD-fabric-studio-api, Compile records and freshness). */
    private final StudioCompileFreshness freshness = new StudioCompileFreshness(deploymentService);
    /** The bytes compile records name, kept per tenant by SHA-256 beside the state file. */
    private final StudioCompiledContractStore compiledStore;
    /** Reads a record's stored envelopes back for contract-diff and verify. */
    private final StudioCompiledContractReader compiledReader;

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
        this.companionStore = StudioCompanionStore.forState(store);
        this.compiledStore = StudioCompiledContractStore.forState(store);
        this.compiledReader = new StudioCompiledContractReader(compiledStore);
        if (store != null) {
            StudioStateStore.State state = store.load();
            state.entriesByTenant().forEach((tenant, entries) ->
                    entriesByTenant.put(tenant, withClaimsOnly("tenant " + tenant, entries)));
            state.assembliesByTenant().forEach((tenant, assemblies) ->
                    assembliesByTenant.put(tenant, new ConcurrentHashMap<>(assemblies)));
            state.layoutsByTenant().forEach((tenant, layouts) ->
                    layoutsByTenant.put(tenant, new ConcurrentHashMap<>(layouts)));
            state.sessionsByTenant().forEach((tenant, sessions) ->
                    sessionsByTenant.put(tenant, new ConcurrentHashMap<>(sessions)));
            state.filesByTenant().forEach((tenant, files) ->
                    filesByTenant.put(tenant, new ConcurrentHashMap<>(files)));
            state.sessionFileLinksByTenant().forEach((tenant, links) ->
                    sessionFileLinksByTenant.put(tenant, List.copyOf(links)));
            // Earlier catalog versions follow the same rule: a draft or proposal that selects one is never served an
            // entry without a claim (LLD-fabric-studio-api, What a catalog entry is).
            state.catalogSnapshotsByTenant().forEach((tenant, snapshots) -> {
                Map<String, StudioCatalogSnapshot> versions = new ConcurrentHashMap<>();
                snapshots.forEach((fileId, snapshot) -> versions.put(fileId, withClaimsOnly(tenant, fileId, snapshot)));
                catalogSnapshotsByTenant.put(tenant, versions);
            });
            state.companionsByTenant().forEach((tenant, records) ->
                    companionsByTenant.put(tenant, new ConcurrentHashMap<>(records)));
            state.environmentsByTenant().forEach((tenant, environments) ->
                    environmentsByTenant.put(tenant, new ConcurrentHashMap<>(environments)));
        }
    }

    public StudioCatalogVisualsResponse listCatalogVisuals(String tenantId) {
        return listCatalogVisuals(tenantId, "");
    }

    /**
     * Query handler: returns either the tenant's active catalog or an immutable
     * catalog version selected from the tenant file registry.
     *
     * <p>Pattern: read-model projector. Catalog file ids are resolved inside the
     * route tenant so a browser cannot bind a draft to another tenant's catalog
     * by guessing a file id.
     */
    public StudioCatalogVisualsResponse listCatalogVisuals(String tenantId, String catalogFileId) {
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries;
        if (catalogFileId != null && !catalogFileId.isBlank()) {
            entries = catalogSnapshotForFile(tenant, catalogFileId).entries();
        } else {
            entries = entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
            ensureCatalogFile(tenant, entries, "");
        }
        StudioCatalogVisualsResponse snapshot = response(entries);
        return new StudioCatalogVisualsResponse(
                snapshot.catalogHash(),
                snapshot.entries(),
                List.of(diagnosticArtifact(tenant, "catalog-snapshot", "catalog.json", snapshot)));
    }

    /**
     * Query handler: returns tenant-isolated file registry rows, optionally
     * scoped by file type and session/correlation links.
     *
     * <p>Pattern: repository read facade. Before listing catalog files, the
     * active tenant catalog is materialized as a registry version so New Draft
     * can show a deterministic latest catalog even on a fresh server.
     */
    public List<StudioFileRecord> listFiles(
            String tenantId,
            String fileType,
            String sessionId,
            String correlationId
    ) {
        String tenant = normalizeTenant(tenantId);
        ensureCatalogFile(tenant, entriesByTenant.computeIfAbsent(tenant, this::catalogEntries), "");
        Set<String> scopedFileIds = linkedFileIds(tenant, sessionId, correlationId);
        String normalizedType = fileType == null ? "" : fileType.trim();
        return filesByTenant
                .getOrDefault(tenant, Map.of())
                .values()
                .stream()
                .filter(file -> normalizedType.isBlank() || normalizedType.equalsIgnoreCase(file.fileType()))
                .filter(file -> scopedFileIds == null || scopedFileIds.contains(file.fileId()))
                .sorted(Comparator
                        .comparing(StudioFileRecord::createdAt).reversed()
                        .thenComparing(Comparator.comparingInt(StudioFileRecord::version).reversed())
                        .thenComparing(StudioFileRecord::fileName))
                .toList();
    }

    /**
     * Query handler: projects tenant draft sessions into compact historical
     * sidebar rows sorted by most recent activity.
     */
    public List<StudioSessionHistoryItem> listSessionHistory(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assemblyFilter = assemblyId == null ? "" : assemblyId.trim();
        Map<String, Long> linkedCounts = linkedFileCountBySession(tenant);
        return sessionsByTenant
                .getOrDefault(tenant, Map.of())
                .values()
                .stream()
                .filter(session -> assemblyFilter.isBlank() || normalizeAssembly(assemblyFilter).equals(session.assemblyId()))
                .map(session -> new StudioSessionHistoryItem(
                        session.tenantId(),
                        session.assemblyId(),
                        session.sessionId(),
                        session.displayName(),
                        session.sessionType(),
                        session.status(),
                        session.baseCatalogHash(),
                        session.catalogFileId(),
                        session.createdAt(),
                        session.updatedAt(),
                        session.lastOpenedAt(),
                        Math.toIntExact(linkedCounts.getOrDefault(session.sessionId(), 0L)),
                        session.intentLog().size()))
                .sorted(Comparator
                        .comparing(StudioSessionHistoryItem::lastOpenedAt, Comparator.reverseOrder())
                        .thenComparing(StudioSessionHistoryItem::updatedAt, Comparator.reverseOrder())
                        .thenComparing(StudioSessionHistoryItem::sessionId))
                .toList();
    }

    /**
     * Download accessor: serves tenant-owned file registry content when Studio
     * owns the bytes and the optional hash pin matches.
     */
    public Optional<StudioAssetContent> fileContent(String tenantId, String fileId, String requestedSha256) {
        String tenant = normalizeTenant(tenantId);
        StudioFileRecord file = filesByTenant
                .getOrDefault(tenant, Map.of())
                .get(fileId == null ? "" : fileId.trim());
        if (file == null) {
            return Optional.empty();
        }
        Optional<StudioAssetContent> content = contentForFileRecord(tenant, file);
        return content.filter(asset -> requestedSha256 == null
                || requestedSha256.isBlank()
                || asset.sha256().equals(requestedSha256));
    }

    /**
     * Snapshot factory: captures the tenant catalog read model as portable JSON
     * while preserving the catalog hash Fabric uses for draft grounding.
     */
    public StudioCatalogSnapshot saveCatalogSnapshot(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
        StudioCatalogVisualsResponse catalog = response(entries);
        ensureCatalogFile(tenant, entries, "");
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
        // All or nothing: a snapshot carrying an entry without a claim is refused before anything is stored.
        List<String> withoutClaim = snapshot.entries().stream()
                .filter(entry -> !entry.admitted())
                .map(StudioVisualCatalogEntry::catalogEntryId)
                .toList();
        if (!withoutClaim.isEmpty()) {
            throw new IllegalArgumentException("CATALOG_SNAPSHOT_INVALID: these entries carry no claim read from a component "
                    + "artifact, so they cannot be catalog entries: " + String.join(", ", withoutClaim));
        }
        List<StudioVisualCatalogEntry> entries = snapshot.entries().stream()
                .sorted(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId))
                .toList();
        entriesByTenant.put(tenant, List.copyOf(entries));
        ensureCatalogFile(tenant, entries, "Loaded Catalog");
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
        List<StudioVisualCatalogEntry> entries = new ArrayList<>(entriesByTenant.computeIfAbsent(tenant, this::catalogEntries));
        List<StudioClaimVerificationResult> results = new ArrayList<>();
        List<ResolvedClaimBundleEntry> resolvedClaims = new ArrayList<>();

        for (StudioComponentArtifactDraft artifact : safeRequest.artifacts()) {
            if (artifact.fileName() == null || artifact.fileName().isBlank()) {
                results.add(new StudioClaimVerificationResult("", "REJECTED", "", "", List.of("fileName is required"),
                        List.of(new StudioDcpDiagnostic("ERROR", "CLAIM_MALFORMED", "fileName", "fileName is required"))));
                continue;
            }
            StudioClaimAdmissionValidator.AdmissionValidation validation = claimAdmissionValidator.validate(artifact);
            if (validation.companion()) {
                results.add(admitCompanion(tenant, artifact.fileName(), validation));
                continue;
            }
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
            // The jar itself is kept, content-addressed, and the entry is grounded with it, so a signed compile bundles
            // the documentation artifacts it carries and an emit can deliver a hosted component's pinned bytes
            // (LLD-fabric-studio-api, Tenant Catalog and Assets [P2d]).
            Path storedJar = companionStore.store(tenant, validation.artifactBytes());
            String claimHash = "sha256:" + new com.unfurl.fabric.catalog.CatalogManifestCodec()
                    .computeClaimHash(validation.claim());
            String artifactSha = validation.artifactSha();
            CatalogEntry runtimeEntry = catalogEntryFromManifest(validation.claimYaml(), artifactSha, storedJar);
            // A package visual manifest naming this entry decorates it as it decorates scanned ones.
            StudioVisualCatalogEntry entry = packageVisualAssets.decorate(List.of(admittedVisualEntry(entryId, runtimeEntry,
                    claimHash, artifactSha, validation.claimYaml())), assetRoot).get(0);
            entries.removeIf(existing -> existing.catalogEntryId().equals(entryId));
            entries.add(entry);
            catalogEntriesByTenant
                    .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                    .put(entryId, runtimeEntry);
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
        ensureCatalogFile(tenant, entries, "Admitted Catalog");
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
     * Companion admission (HLD-in-process-component-hosting §3): stores a companion jar's bytes and records its
     * coordinates for the tenant. Identical bytes again are idempotent; different bytes under admitted coordinates are
     * refused with {@code CATALOG_COMPANION_CONFLICT}, naming the stored file and hash, and the stored one is kept.
     * Companions are not composable, so they never enter the visual catalog.
     */
    private StudioClaimVerificationResult admitCompanion(
            String tenant, String fileName, StudioClaimAdmissionValidator.AdmissionValidation validation) {
        String coordinates = validation.companionCoordinates();
        String sha = validation.artifactSha().startsWith("sha256:")
                ? validation.artifactSha().substring("sha256:".length()) : validation.artifactSha();
        Map<String, StudioCompanionRecord> records = companionsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        synchronized (records) {
            StudioCompanionRecord stored = records.get(coordinates);
            if (stored != null && !stored.sha256().equals(sha)) {
                String message = com.unfurl.fabric.catalog.CompanionCoordinates.CONFLICT + ": " + coordinates
                        + " is already admitted for this tenant as " + stored.fileName() + " (sha256 " + stored.sha256()
                        + "), and " + fileName + " has different bytes (sha256 " + sha + ")";
                return new StudioClaimVerificationResult(fileName, "REJECTED", "", "", List.of(message), List.of(
                        new StudioDcpDiagnostic("ERROR", com.unfurl.fabric.catalog.CompanionCoordinates.CONFLICT, "artifact", message)));
            }
            companionStore.store(tenant, validation.artifactBytes());
            if (stored == null) {
                records.put(coordinates, new StudioCompanionRecord(coordinates, sha, fileName));
            }
        }
        return new StudioClaimVerificationResult(fileName, "VERIFIED", "companion:" + coordinates, "",
                List.of("admitted as companion " + coordinates + " (sha256 " + sha + ")"), List.of());
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
        List<StudioVisualCatalogEntry> existing = entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
        List<StudioVisualCatalogEntry> entries = existing.stream()
                .filter(entry -> !catalogEntryId.equals(entry.catalogEntryId()))
                .sorted(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId))
                .toList();
        if (entries.size() == existing.size()) {
            throw new IllegalArgumentException("catalog entry not found: " + catalogEntryId);
        }
        entriesByTenant.put(tenant, List.copyOf(entries));
        ensureCatalogFile(tenant, entries, "Curated Catalog");
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
     * Factory: builds the runtime catalog entry for a component from the exact manifest text read from its artifact and
     * the SHA-256 computed from the artifact's bytes. Used at admission and to rebuild an entry from persisted Studio
     * state; there is no fallback, so an entry exists only for a complete manifest (DCP HLD-C §3.10).
     *
     * @param manifestYaml the {@code META-INF/unfurl-catalog.yaml} text read from the artifact.
     * @param artifactSha  the artifact hash, {@code sha256:<hex>} or bare hex.
     * @param jar          the admitted jar in the tenant's artifact store, or null when its bytes are not kept; the
     *                     entry carries it as a scanned entry carries its jar (documentation artifacts, hosted jars).
     * @return the catalog entry, with the claim hash computed from the manifest's claim.
     * @throws CatalogScanException when the manifest is malformed or incomplete.
     */
    private CatalogEntry catalogEntryFromManifest(String manifestYaml, String artifactSha, Path jar) {
        CatalogManifestCodec codec = new CatalogManifestCodec();
        ParsedManifest parsed = codec.parse(manifestYaml.getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor artifact = new ArtifactDescriptor(
                parsed.authoredArtifact().coordinates(),
                parsed.authoredArtifact().packaging(),
                parsed.authoredArtifact().source(),
                artifactDescriptorSha(artifactSha),
                parsed.authoredArtifact().signature());
        return new CatalogEntry(
                artifact,
                new ClaimDescriptor(parsed.claim(), codec.computeClaimHash(parsed.claim())),
                // The full catalog metadata, hosting blocks included, so Studio places hosted components as the CLI does.
                parsed.metadata(),
                parsed.componentShapeProfile(),
                jar);
    }

    /**
     * The admitted jar of an artifact hash in the tenant's artifact store (LLD-fabric-studio-api, Tenant Catalog and
     * Assets [P2d]), or null when Studio does not hold its bytes (a jar admitted before Studio kept them).
     */
    private Path storedJar(String tenant, String artifactSha) {
        String sha = bareSha(artifactSha);
        if (sha == null || !sha.matches("[0-9a-f]{64}")) return null;
        Path stored = companionStore.path(tenant, sha);
        return Files.isRegularFile(stored) ? stored : null;
    }

    /**
     * Projector: turns a verified uploaded catalog entry into the Studio visual-catalog
     * shape. The catalog entry id stays upload-scoped, while the visual and dynamic
     * metadata preserve the parsed manifest shape profile, and the entry keeps the manifest text read from the jar so
     * persisted Studio state rebuilds the same claim without the original uploaded JAR.
     */
    private StudioVisualCatalogEntry admittedVisualEntry(
            String entryId,
            CatalogEntry runtimeEntry,
            String claimHash,
            String artifactSha,
            String catalogManifest
    ) {
        Claim claim = runtimeEntry.claimDescriptor().claim();
        List<String> capabilities = claim.offers() == null
                ? List.of()
                : claim.offers().stream().map(Offer::capability).toList();
        List<String> requiredCapabilities = requiredCapabilitiesFromClaim(claim);
        ComponentShapeProfile shapeProfile = runtimeEntry.componentShapeProfile();
        String category = shapeProfile == null
                ? claim.identity() == null || claim.identity().kind() == null
                        ? "COMPONENT"
                        : claim.identity().kind().toString()
                : shapeProfile.defaultShape().name();
        String fallbackKind = shapeProfile == null ? "CUBE" : fallbackShapeKindFor(shapeProfile.defaultShape().name());
        List<String> rawNeeds = claim.dependencies() == null || claim.dependencies().needs() == null
                ? List.of()
                : List.copyOf(claim.dependencies().needs());
        return new StudioVisualCatalogEntry(
                entryId,
                claimHash,
                artifactSha,
                visual(category, fallbackKind, capabilities, requiredCapabilities, optionalCapabilitiesFromClaim(claim)),
                dynamicComposition("COMPONENT", List.of(), rawNeeds, rawOptionalNeeds(claim), shapeProfile),
                Map.of("visualManifestHash", sha256("visual:" + entryId), "assets", List.of()),
                List.of(),
                catalogManifest);
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
            addRequiredCapability(required, "agent.run", "^1", Map.of("execution_modes", List.of("harness")));
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
        addRequiredCapability(required, capability, versionRange, Map.of());
    }

    /**
     * Builder helper: adds or upgrades a required capability with governed DCP offer-detail constraints.
     *
     * @param required             insertion-ordered capability accumulator.
     * @param capability           DCP capability name.
     * @param versionRange         acceptable capability version range.
     * @param requiredOfferDetails DCP offer-interface detail subset that must match.
     */
    private void addRequiredCapability(
            Map<String, CapabilityRequirement> required,
            String capability,
            String versionRange,
            Map<String, Object> requiredOfferDetails
    ) {
        if (capability == null || capability.isBlank()) {
            return;
        }
        CapabilityRequirement next = CapabilityRequirement.requiredOf(capability, versionRange, requiredOfferDetails);
        required.merge(capability, next, (existing, candidate) -> {
            if (existing.requiredOfferDetails().isEmpty() && !candidate.requiredOfferDetails().isEmpty()) {
                return candidate;
            }
            return existing;
        });
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

    /**
     * Adapter: handles one Studio authoring turn by resolving the tenant/session
     * context, delegating to Foundry when configured, or using the deterministic
     * local bridge to ask grounded catalog questions and emit guarded proposals.
     */
    public synchronized StudioAuthoringConverseResponse converseAuthoring(StudioAuthoringConverseRequest request) {
        StudioAuthoringConverseRequest safe = request == null
                ? new StudioAuthoringConverseRequest("tenant-local", "assembly-demo", "", List.of(), "")
                : request;
        String tenant = normalizeTenant(safe.tenantId());
        String assembly = normalizeAssembly(safe.assemblyId());
        StudioDraftSession session = authoringSession(
                tenant,
                assembly,
                safe.sessionId(),
                safe.catalogFileId(),
                safe.displayName());

        if (authoringInvocable != null) {
            return converseViaDcp(safe, tenant, assembly, session);
        }

        String prompt = safe.userMessage().trim();
        String lowered = prompt.toLowerCase();
        List<StudioVisualCatalogEntry> entries = authoringCatalogEntries(tenant, session);
        Map<String, Object> questionAnswers = canonicalAuthoringQuestionAnswers(safe.questionAnswers());

        if (entries.isEmpty()) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "This tenant does not have admitted catalog components to compose from.",
                    List.of("catalog.empty"));
        }

        if (!safe.actionContext().isEmpty() && questionAnswers.isEmpty()) {
            return StudioAuthoringConverseResponse.clarify(
                    session.sessionId(),
                    "I can help with this draft edit, but I need the configuration and impact choices first.",
                    authoringActionQuestions(safe.actionContext(), entries));
        }
        if (!safe.actionContext().isEmpty()) {
            return deterministicActionProposal(session, tenant, safe.actionContext(), questionAnswers, entries);
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

        Optional<StudioVisualCatalogEntry> answeredComponent = selectedEntryFromAnswers(questionAnswers, entries);
        if (answeredComponent.isEmpty() && shouldAskAuthoringDiscovery(prompt, lowered, entries)) {
            return StudioAuthoringConverseResponse.clarify(
                    session.sessionId(),
                    "Let's ground this draft in the tenant catalog before I propose a Fabric contract.",
                    authoringDiscoveryQuestions(tenant, session, entries));
        }

        StudioVisualCatalogEntry selected = answeredComponent.orElseGet(() -> entries.stream()
                .filter(entry -> promptMentionsEntry(prompt, entry))
                .findFirst()
                .orElse(entries.get(0)));
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

    /**
     * Adapter: projects Fabric's tenant-scoped catalog/session read models into
     * the neutral DCP `agent.run` invocation consumed by the Foundry host.
     */
    private StudioAuthoringConverseResponse converseViaDcp(
            StudioAuthoringConverseRequest request, String tenant, String assembly, StudioDraftSession session) {
        List<StudioVisualCatalogEntry> entries = authoringCatalogEntries(tenant, session);
        StudioCatalogVisualsResponse catalogSnapshot = response(entries);
        List<StudioAuthoringConversationMessage> requestConversation =
                request.conversation() == null ? List.of() : request.conversation();
        Map<String, Object> questionAnswers = canonicalAuthoringQuestionAnswers(request.questionAnswers());
        boolean focusedTurn = focusedAuthoringContextTurn(questionAnswers, request.actionContext());
        int conversationLimit = focusedTurn
                ? AUTHORING_CONTEXT_FOLLOWUP_CONVERSATION_LIMIT
                : AUTHORING_CONTEXT_CONVERSATION_LIMIT;
        int catalogFilesLimit = focusedTurn
                ? AUTHORING_CONTEXT_FOLLOWUP_CATALOG_FILE_LIMIT
                : AUTHORING_CONTEXT_CATALOG_FILE_LIMIT;
        int sessionHistoryLimit = focusedTurn
                ? AUTHORING_CONTEXT_FOLLOWUP_SESSION_HISTORY_LIMIT
                : AUTHORING_CONTEXT_SESSION_HISTORY_LIMIT;
        int catalogPreviewLimit = focusedTurn
                ? AUTHORING_CONTEXT_FOLLOWUP_CATALOG_PREVIEW_LIMIT
                : AUTHORING_CONTEXT_CATALOG_PREVIEW_LIMIT;
        List<Map<String, Object>> conversation = boundedAuthoringConversation(requestConversation, conversationLimit);
        List<StudioFileRecord> catalogFiles = listFiles(tenant, "CATALOG", "", "");
        List<StudioSessionHistoryItem> sessionHistory = listSessionHistory(tenant, assembly);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", tenant);
        input.put("assemblyId", assembly);
        input.put("sessionId", session.sessionId());
        input.put("userMessage", request.userMessage());
        input.put("questionAnswers", questionAnswers);
        input.put("conversation", conversation);
        input.put("catalogHash", catalogSnapshot.catalogHash());
        input.put("session", authoringSessionMap(session));
        input.put("catalogFile", authoringCatalogFileMap(tenant, session.catalogFileId()));
        input.put("catalogFiles", boundedAuthoringCatalogFiles(catalogFiles, catalogFilesLimit, session.catalogFileId()));
        input.put("sessionHistory", boundedAuthoringSessionHistory(sessionHistory, sessionHistoryLimit, session.sessionId()));
        input.put("catalogSummary", authoringCatalogSummary(catalogSnapshot, session, entries, catalogPreviewLimit));
        input.put("catalogPreview", authoringCatalogPreviewForTurn(
                entries, questionAnswers, request.actionContext(), focusedTurn, catalogPreviewLimit));
        input.put("contextLimits", authoringContextLimits(
                requestConversation,
                catalogFiles,
                sessionHistory,
                entries,
                conversationLimit,
                catalogFilesLimit,
                sessionHistoryLimit,
                catalogPreviewLimit));
        input.put("toolHydration", authoringToolHydration(tenant, assembly, session));
        input.put("actionContext", request.actionContext());

        // DCP invocation metadata: request Foundry's governed harness mode so the
        // authoring agent can execute tools in a loop before returning a terminal answer.
        Map<String, Object> metadata = Map.of("executionMode", "harness");
        ContractInvocation invocation = new ContractInvocation(
                "urn:unfurl:fabric:authoring", "agent.run", "unfurl-fabric", "unfurl-foundry",
                input, session.sessionId(), Map.of(), null, metadata);
        ContractInvocationResult result = authoringInvocable.invoke(invocation, ExecutionContext.empty());
        if (!result.success()) {
            return StudioAuthoringConverseResponse.gap(session.sessionId(),
                    result.errorMessage() == null ? "the authoring agent could not respond." : result.errorMessage(),
                    List.of());
        }
        return mapAuthoringOutput(tenant, session.sessionId(), result.output(), entries);
    }

    /**
     * Context projector: emits bounded catalog metadata for Foundry prompts. The
     * full catalog stays behind Studio tools, so the model sees enough to ask
     * grounded questions without receiving every visual manifest or claim row.
     */
    private Map<String, Object> authoringCatalogSummary(
            StudioCatalogVisualsResponse catalogSnapshot,
            StudioDraftSession session,
            List<StudioVisualCatalogEntry> entries,
            int previewLimit
    ) {
        Set<String> capabilities = new TreeSet<>();
        for (StudioVisualCatalogEntry entry : entries == null ? List.<StudioVisualCatalogEntry>of() : entries) {
            portsOfKind(entry.visualManifest(), "OFFER").stream()
                    .map(PortDescriptor::capability)
                    .forEach(capabilities::add);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalogHash", catalogSnapshot.catalogHash());
        result.put("catalogFileId", session == null ? "" : session.catalogFileId());
        result.put("entryCount", entries == null ? 0 : entries.size());
        result.put("previewLimit", previewLimit);
        result.put("omittedEntryCount", Math.max(0, (entries == null ? 0 : entries.size()) - previewLimit));
        result.put("offeredCapabilities", List.copyOf(capabilities));
        return result;
    }

    /**
     * Context projector: returns a small label/id/capability list for first-turn
     * clarification. Agents must call `fabric.catalog-query` when they need more
     * rows or detailed port metadata.
     */
    private List<Map<String, Object>> authoringCatalogPreview(List<StudioVisualCatalogEntry> entries, int limit) {
        return (entries == null ? List.<StudioVisualCatalogEntry>of() : entries).stream()
                .limit(Math.max(0, limit))
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("catalogEntryId", entry.catalogEntryId());
                    item.put("displayName", labelForCatalogEntry(entry.catalogEntryId()));
                    item.put("offeredCapabilities", portsOfKind(entry.visualManifest(), "OFFER").stream()
                            .map(PortDescriptor::capability)
                            .toList());
                    return item;
                })
                .toList();
    }

    /**
     * Context projector strategy: keeps first-turn catalog previews broad enough
     * for discovery, then narrows follow-up turns to entries named by structured
     * answers or action context. This preserves the prompt budget while keeping
     * exact ids visible for the model's explanation.
     */
    private List<Map<String, Object>> authoringCatalogPreviewForTurn(
            List<StudioVisualCatalogEntry> entries,
            Map<String, Object> questionAnswers,
            Map<String, Object> actionContext,
            boolean focusedTurn,
            int limit
    ) {
        List<StudioVisualCatalogEntry> safeEntries = entries == null ? List.of() : entries;
        if (!focusedTurn) {
            return authoringCatalogPreview(safeEntries, limit);
        }
        LinkedHashSet<String> selectedIds = selectedCatalogEntryIds(questionAnswers, actionContext, safeEntries);
        List<StudioVisualCatalogEntry> focusedEntries = new ArrayList<>();
        for (String selectedId : selectedIds) {
            safeEntries.stream()
                    .filter(entry -> selectedId.equals(entry.catalogEntryId()))
                    .findFirst()
                    .ifPresent(focusedEntries::add);
        }
        int max = Math.max(0, limit);
        if (focusedEntries.size() < max) {
            for (StudioVisualCatalogEntry entry : safeEntries) {
                if (focusedEntries.size() >= max) {
                    break;
                }
                if (!selectedIds.contains(entry.catalogEntryId())) {
                    focusedEntries.add(entry);
                }
            }
        }
        return authoringCatalogPreview(focusedEntries, max);
    }

    /**
     * Selection extractor: resolves answer/action fields that are expected to
     * contain catalog entry ids or safe aliases into exact selected-catalog ids.
     */
    private LinkedHashSet<String> selectedCatalogEntryIds(
            Map<String, Object> questionAnswers,
            Map<String, Object> actionContext,
            List<StudioVisualCatalogEntry> entries
    ) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        collectCatalogEntryAnswer(selected, questionAnswers, entries,
                "startingComponent",
                "rootComponent",
                "catalogEntryId",
                "modelProviderCatalogEntryId",
                "toolImplementationCatalogEntryId",
                "ragCorpusCatalogEntryId",
                "vectorStoreCatalogEntryId",
                "componentCatalogEntryId",
                "newCatalogEntryId",
                "selectedCatalogEntryId");
        collectCatalogEntryAnswer(selected, actionContext, entries,
                "catalogEntryId",
                "modelProviderCatalogEntryId",
                "toolImplementationCatalogEntryId",
                "ragCorpusCatalogEntryId",
                "vectorStoreCatalogEntryId",
                "componentCatalogEntryId",
                "newCatalogEntryId",
                "selectedCatalogEntryId");
        return selected;
    }

    /**
     * Answer collector: supports single or list-valued catalog answers and uses
     * the same alias resolver as proposal grounding before adding an id.
     */
    private void collectCatalogEntryAnswer(
            Set<String> selected,
            Map<String, Object> source,
            List<StudioVisualCatalogEntry> entries,
            String... keys
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    addCatalogEntryAnswer(selected, entries, answerAsString(item));
                }
            } else {
                addCatalogEntryAnswer(selected, entries, answerAsString(value));
            }
        }
    }

    /**
     * Answer resolver: adds a canonical catalog entry id when the selected
     * catalog can resolve the answer uniquely.
     */
    private void addCatalogEntryAnswer(Set<String> selected, List<StudioVisualCatalogEntry> entries, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        canonicalCatalogEntryId(entries, value).ifPresent(selected::add);
    }

    /**
     * Context projector: gives Foundry exact Studio tool names and default
     * tenant/session arguments for expanding compact authoring context.
     */
    private Map<String, Object> authoringToolHydration(String tenant, String assembly, StudioDraftSession session) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("tenantId", tenant);
        defaults.put("assemblyId", assembly);
        defaults.put("sessionId", session == null ? "" : session.sessionId());
        defaults.put("catalogFileId", session == null ? "" : session.catalogFileId());
        return Map.of(
                "catalogQueryTool", "fabric.catalog-query",
                "dynamicDcpTool", "fabric.dynamic-dcp-project",
                "defaults", defaults);
    }

    /**
     * Strategy: decides whether deterministic fallback must ask grounded
     * discovery questions before it can safely produce catalog-backed intents.
     */
    private boolean shouldAskAuthoringDiscovery(
            String prompt,
            String lowered,
            List<StudioVisualCatalogEntry> entries
    ) {
        if (prompt.length() < 12 || lowered.matches("^(build|make|create|author|compose|app|service|contract)\\s*$")) {
            return true;
        }
        if (entries.size() > 1
                && (lowered.contains("assembly") || lowered.contains("catalog") || lowered.contains("draft"))
                && entries.stream().noneMatch(entry -> promptMentionsEntry(prompt, entry))) {
            return true;
        }
        return false;
    }

    /**
     * Projector: builds typed clarification questions from tenant-owned catalog
     * file rows and admitted catalog entries, preserving exact ids in options so
     * a follow-up answer can be replayed through the normal authoring endpoint.
     */
    private List<StudioAuthoringQuestion> authoringDiscoveryQuestions(
            String tenant,
            StudioDraftSession session,
            List<StudioVisualCatalogEntry> entries
    ) {
        List<StudioAuthoringQuestion> questions = new ArrayList<>();
        List<StudioFileRecord> catalogFiles = listFiles(tenant, "CATALOG", "", "");
        if (catalogFiles.size() > 1 && (session.catalogFileId() == null || session.catalogFileId().isBlank())) {
            questions.add(new StudioAuthoringQuestion(
                    "catalogFileId",
                    "Which tenant catalog version should I use?",
                    "SINGLE_SELECT",
                    catalogFiles.stream().map(this::authoringCatalogFileOption).toList()));
        }
        if (entries.size() > 1) {
            questions.add(new StudioAuthoringQuestion(
                    "startingComponent",
                    "Which admitted component should be the starting component for this assembly?",
                    "SINGLE_SELECT",
                    entries.stream()
                            .limit(12)
                            .map(this::authoringComponentOption)
                            .toList()));
        }
        questions.add(new StudioAuthoringQuestion(
                "assemblyGoal",
                "What should this assembly do once the starting component is selected?",
                "TEXT",
                List.of()));
        return questions;
    }

    /**
     * Strategy: resolves structured clarification answers into an admitted
     * catalog entry without asking the model to parse display text.
     */
    private Optional<StudioVisualCatalogEntry> selectedEntryFromAnswers(
            Map<String, Object> answers,
            List<StudioVisualCatalogEntry> entries
    ) {
        String answered = firstNonBlank(
                answerAsString(answers.get("startingComponent")),
                answerAsString(answers.get("starting_component")),
                answerAsString(answers.get("rootComponent")),
                answerAsString(answers.get("root_component")),
                answerAsString(answers.get("catalogEntryId")),
                answerAsString(answers.get("catalog_entry_id")));
        if (answered.isBlank()) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> answered.equals(entry.catalogEntryId())
                        || answered.contains(entry.catalogEntryId())
                        || labelForCatalogEntry(entry.catalogEntryId()).equalsIgnoreCase(answered))
                .findFirst();
    }

    /**
     * Deterministic fallback strategy: turns an answered visual edit assist
     * request into a catalog-guarded proposal for the supported local ADD case.
     * Foundry remains the normal path for richer configuration reasoning.
     */
    private StudioAuthoringConverseResponse deterministicActionProposal(
            StudioDraftSession session,
            String tenant,
            Map<String, Object> actionContext,
            Map<String, Object> questionAnswers,
            List<StudioVisualCatalogEntry> entries
    ) {
        String operation = String.valueOf(actionContext.getOrDefault("operation", "CONFIGURE")).toUpperCase(Locale.ROOT);
        if (!"ADD_COMPONENT".equals(operation)) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "The deterministic authoring bridge can only propose answered ADD_COMPONENT actions.",
                    List.of(operation));
        }
        String catalogEntryId = firstNonBlank(
                answerAsString(questionAnswers.get("catalogEntryId")),
                asString(actionContext.get("catalogEntryId")));
        Optional<StudioVisualCatalogEntry> selected = entries.stream()
                .filter(entry -> entry.catalogEntryId().equals(catalogEntryId))
                .findFirst();
        if (selected.isEmpty()) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "The selected component is not currently admitted for this tenant.",
                    List.of(catalogEntryId.isBlank() ? "catalogEntryId" : catalogEntryId));
        }
        Optional<StudioIntentResponse> rejection = rejectIntentAgainstCatalog(
                tenant,
                "ADD_COMPONENT",
                Map.of("catalogEntryId", selected.get().catalogEntryId()));
        if (rejection.isPresent()) {
            return StudioAuthoringConverseResponse.gap(
                    session.sessionId(),
                    "The selected component is not currently admitted for this tenant.",
                    List.of(selected.get().catalogEntryId()));
        }
        String capability = firstOfferedCapability(selected.get()).orElse(slug(selected.get().catalogEntryId()).replace('-', '.'));
        Map<String, Object> addIntent = new LinkedHashMap<>();
        addIntent.put("type", "ADD_COMPONENT");
        addIntent.put("catalogEntryId", selected.get().catalogEntryId());
        StudioAuthoringProposal proposal = new StudioAuthoringProposal(
                """
                requiredCapabilities:
                  - capability: %s
                    capabilityVersion: ^1
                """.formatted(capability),
                List.of(addIntent),
                new StudioDeploymentPolicyDraft(
                        List.of("CONTAINERIZED_SERVICE"),
                        List.of(),
                        List.of(),
                        new StudioDeploymentRuntimeDraft(null, null, true, null, null)),
                List.of("proposal generated from structured action answers; review before accepting"));
        return StudioAuthoringConverseResponse.proposal(
                session.sessionId(),
                "I prepared a governed add-component proposal from the structured answers.",
                proposal);
    }

    /**
     * Projector: turns UI-originated visual edit context into targeted
     * clarification questions. This keeps add/remove/replace/substrate assist
     * inside the authoring-agent contract while leaving mutation to Studio
     * intents after the operator accepts a proposal.
     */
    private List<StudioAuthoringQuestion> authoringActionQuestions(
            Map<String, Object> actionContext,
            List<StudioVisualCatalogEntry> entries
    ) {
        String operation = String.valueOf(actionContext.getOrDefault("operation", "CONFIGURE")).toUpperCase(Locale.ROOT);
        String catalogEntryId = asString(actionContext.get("catalogEntryId"));
        String label = firstNonBlank(
                asString(actionContext.get("componentLabel")),
                asString(actionContext.get("selectedComponentLabel")),
                catalogEntryId == null || catalogEntryId.isBlank() ? "" : labelForCatalogEntry(catalogEntryId),
                "the selected item");
        List<String> dependencyPorts = actionStringList(actionContext.get("dependencyPorts"));
        List<String> substratePorts = actionStringList(actionContext.get("substratePorts"));
        if (dependencyPorts.isEmpty() && catalogEntryId != null && !catalogEntryId.isBlank()) {
            dependencyPorts = entries.stream()
                    .filter(entry -> catalogEntryId.equals(entry.catalogEntryId()))
                    .findFirst()
                    // Only required ports need a binding before the component is added; optional ones may stay unbound.
                    .map(entry -> portsOfKind(entry.visualManifest(), "DEPENDENCY").stream()
                            .filter(port -> !port.optional())
                            .map(PortDescriptor::capability)
                            .toList())
                    .orElse(List.of());
        }
        List<StudioAuthoringQuestion> questions = new ArrayList<>();
        switch (operation) {
            case "ADD_COMPONENT" -> {
                questions.add(new StudioAuthoringQuestion(
                        "configurationMode",
                        "How should " + label + " be configured before adding it?",
                        "SINGLE_SELECT",
                        options("Use catalog defaults", "Bind required ports now", "Add now and configure later")));
                if (!dependencyPorts.isEmpty() || !substratePorts.isEmpty()) {
                    questions.add(new StudioAuthoringQuestion(
                            "bindingReferences",
                            "Which provider, secret, or config references should satisfy "
                                    + joinedPortSummary(dependencyPorts, substratePorts) + "?",
                            "TEXT",
                            List.of()));
                }
                questions.add(new StudioAuthoringQuestion(
                        "externalConnections",
                        "Does this component need external endpoints, credentials, queues, stores, or model providers?",
                        "TEXT",
                        List.of()));
            }
            case "REMOVE_COMPONENT" -> {
                questions.add(new StudioAuthoringQuestion(
                        "removalImpact",
                        "What should happen to dependencies, connections, and substrate bindings owned by " + label + "?",
                        "SINGLE_SELECT",
                        options("Remove component and dependent connections", "Replace before removing", "Cancel removal")));
                questions.add(new StudioAuthoringQuestion(
                        "stateCleanup",
                        "Any state, data, secret, or external connection cleanup that must be preserved or handed off?",
                        "TEXT",
                        List.of()));
            }
            case "REPLACE_COMPONENT" -> {
                questions.add(new StudioAuthoringQuestion(
                        "replacementScope",
                        "How should Fabric handle bindings while replacing " + label + "?",
                        "SINGLE_SELECT",
                        options("Preserve compatible bindings", "Reconfigure all bindings", "Review alternatives first")));
                questions.add(new StudioAuthoringQuestion(
                        "replacementConfiguration",
                        "What configuration or external connection changes should the replacement use?",
                        "TEXT",
                        List.of()));
            }
            case "CONNECT", "DISCONNECT" -> {
                questions.add(new StudioAuthoringQuestion(
                        "connectionMode",
                        "How should this connection change be validated?",
                        "SINGLE_SELECT",
                        options("Use selected ports", "Ask for compatible ports", "Configure external endpoint")));
                questions.add(new StudioAuthoringQuestion(
                        "connectionConfiguration",
                        "Provide any endpoint, credential reference, protocol, or policy details for this connection.",
                        "TEXT",
                        List.of()));
            }
            default -> {
                questions.add(new StudioAuthoringQuestion(
                        "runtimeTarget",
                        "Which substrate/runtime target should host or satisfy this configuration?",
                        "TEXT",
                        List.of()));
                questions.add(new StudioAuthoringQuestion(
                        "secretAndConfigRefs",
                        "Which secret, config, telemetry, audit, or provider references should be bound?",
                        "TEXT",
                        List.of()));
            }
        }
        questions.add(new StudioAuthoringQuestion(
                "applyPreference",
                "After these answers, should the agent propose Studio intents or only explain the configuration impact?",
                "SINGLE_SELECT",
                options("Propose governed Studio intents", "Explain impact only")));
        return questions;
    }

    /**
     * Answer parser: normalizes one structured clarification answer into the
     * stable value string used by catalog/file lookup helpers.
     */
    private static String answerAsString(Object answer) {
        if (answer instanceof List<?> list) {
            return list.isEmpty() ? "" : asString(list.getFirst());
        }
        return asString(answer) == null ? "" : asString(answer);
    }

    /**
     * Adapter: preserves provider-emitted question ids while also adding the
     * camelCase aliases used by Fabric's deterministic bridge and Foundry prompt
     * guidance. This keeps clarification loops stable when a model emits
     * snake_case ids such as {@code starting_component}.
     */
    private static Map<String, Object> canonicalAuthoringQuestionAnswers(Map<String, Object> answers) {
        if (answers == null || answers.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(answers);
        for (Map.Entry<String, Object> entry : answers.entrySet()) {
            String alias = camelCaseQuestionId(entry.getKey());
            if (!alias.isBlank()) {
                result.putIfAbsent(alias, entry.getValue());
            }
        }
        copyFirstPresent(result, "startingComponent", "starting_component", "rootComponent", "root_component",
                "catalogEntryId", "catalog_entry_id");
        copyFirstPresent(result, "catalogFileId", "catalog_file_id", "selectedCatalogFile", "selected_catalog_file",
                "catalogFile", "catalog_file");
        copyFirstPresent(result, "recursiveCapabilities", "recursive_capabilities");
        return Map.copyOf(result);
    }

    /**
     * Context strategy: switches to focused prompt previews once a turn carries
     * structured clarification answers or UI edit context. First-turn discovery
     * stays broader; follow-up turns rely on hydration tools for expansion.
     */
    private static boolean focusedAuthoringContextTurn(
            Map<String, Object> questionAnswers,
            Map<String, Object> actionContext
    ) {
        return (questionAnswers != null && !questionAnswers.isEmpty())
                || (actionContext != null && !actionContext.isEmpty());
    }

    /**
     * Name projector: converts a lower snake/kebab question id into the camelCase
     * form used by Fabric and Foundry prompts while leaving already-canonical ids
     * unchanged.
     */
    private static String camelCaseQuestionId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (char c : id.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') {
                upperNext = builder.length() > 0;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return builder.toString();
    }

    /**
     * Alias copier: makes one canonical answer key available from the first
     * nonblank synonym without overwriting an explicit canonical value.
     */
    private static void copyFirstPresent(Map<String, Object> answers, String canonicalKey, String... aliases) {
        if (answers.containsKey(canonicalKey) && !answerAsString(answers.get(canonicalKey)).isBlank()) {
            return;
        }
        for (String alias : aliases) {
            Object value = answers.get(alias);
            if (!answerAsString(value).isBlank()) {
                answers.put(canonicalKey, value);
                return;
            }
        }
    }

    /**
     * Option factory: creates simple label=value authoring options for enum-like
     * choices where the operator-facing copy is already the stable value.
     */
    private static List<StudioAuthoringQuestionOption> options(String... values) {
        List<StudioAuthoringQuestionOption> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                result.add(StudioAuthoringQuestionOption.of(value));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Formatting helper: selects the first nonblank label candidate for
     * action-scoped clarification copy without exposing nullable map fields.
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Formatting helper: renders dependency and substrate port names in one
     * operator-facing phrase for configuration questions.
     */
    private static String joinedPortSummary(List<String> dependencyPorts, List<String> substratePorts) {
        List<String> ports = new ArrayList<>();
        ports.addAll(dependencyPorts);
        ports.addAll(substratePorts);
        return ports.isEmpty() ? "the required ports" : String.join(", ", ports);
    }

    /**
     * JSON adapter: normalizes optional action-context lists into strings while
     * dropping blank values that would produce noisy clarification questions.
     */
    private static List<String> actionStringList(Object value) {
        return asStringList(value).stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    /**
     * Formatter: renders a catalog file option with the readable title first
     * and the immutable file id/hash retained for exact follow-up selection.
     */
    private StudioAuthoringQuestionOption authoringCatalogFileOption(StudioFileRecord file) {
        return new StudioAuthoringQuestionOption(
                "%s (v%s, %s)".formatted(file.fileTitle(), file.version(), file.sha256()),
                file.fileId());
    }

    /**
     * Formatter: renders a component option with a human-readable label while
     * keeping the exact catalogEntryId visible for governed intent creation.
     */
    private StudioAuthoringQuestionOption authoringComponentOption(StudioVisualCatalogEntry entry) {
        return new StudioAuthoringQuestionOption(labelForCatalogEntry(entry.catalogEntryId()), entry.catalogEntryId());
    }

    /**
     * Context projector: returns the catalog entries bound to the authoring
     * session, using the session's immutable catalog file id when present.
     */
    private List<StudioVisualCatalogEntry> authoringCatalogEntries(String tenantId, StudioDraftSession session) {
        String tenant = normalizeTenant(tenantId);
        if (session != null && session.catalogFileId() != null && !session.catalogFileId().isBlank()) {
            return catalogSnapshotForFile(tenant, session.catalogFileId()).entries();
        }
        return entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
    }

    /**
     * Context projector: emits the current authoring session as a compact
     * Foundry input object without exposing mutable service internals.
     */
    private Map<String, Object> authoringSessionMap(StudioDraftSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (session == null) {
            return result;
        }
        result.put("tenantId", session.tenantId());
        result.put("assemblyId", session.assemblyId());
        result.put("sessionId", session.sessionId());
        result.put("displayName", session.displayName());
        result.put("baseCatalogHash", session.baseCatalogHash());
        result.put("catalogFileId", session.catalogFileId());
        result.put("sessionType", session.sessionType());
        result.put("status", session.status());
        result.put("sceneRevision", session.sceneRevision());
        result.put("intentCount", session.intentLog().size());
        return result;
    }

    /**
     * Context projector: keeps only the latest conversation turns in the
     * prompt payload. Full chat replay belongs in Studio state, while Foundry
     * receives enough local context plus the current structured answers.
     */
    private List<Map<String, Object>> boundedAuthoringConversation(
            List<StudioAuthoringConversationMessage> conversation,
            int limit
    ) {
        List<StudioAuthoringConversationMessage> safe =
                conversation == null ? List.of() : conversation;
        int start = Math.max(0, safe.size() - Math.max(0, limit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (StudioAuthoringConversationMessage message : safe.subList(start, safe.size())) {
            result.add(Map.of(
                    "role", message.role(),
                    "content", message.content()));
        }
        return result;
    }

    /**
     * Context projector: emits only recent tenant catalog file summaries for
     * clarification UX. The selected `catalogFile` remains detailed and agents
     * can call `fabric.file-list` for older versions or file paths.
     */
    private List<Map<String, Object>> boundedAuthoringCatalogFiles(
            List<StudioFileRecord> catalogFiles,
            int limit,
            String selectedCatalogFileId
    ) {
        List<StudioFileRecord> ordered = prioritizeCatalogFile(catalogFiles, selectedCatalogFileId);
        return ordered.stream()
                .limit(Math.max(0, limit))
                .map(this::authoringFilePreviewMap)
                .toList();
    }

    /**
     * Context projector: emits only recent session summaries so repeated
     * authoring turns do not resend the full tenant history. Agents can call
     * `fabric.session-history` when an older session matters.
     */
    private List<Map<String, Object>> boundedAuthoringSessionHistory(
            List<StudioSessionHistoryItem> history,
            int limit,
            String selectedSessionId
    ) {
        List<StudioSessionHistoryItem> ordered = prioritizeSessionHistory(history, selectedSessionId);
        return ordered.stream()
                .limit(Math.max(0, limit))
                .map(this::authoringSessionHistoryPreviewMap)
                .toList();
    }

    /**
     * Ordering strategy: moves the selected catalog file to the front of the
     * prompt preview while preserving the store's existing recency order.
     */
    private List<StudioFileRecord> prioritizeCatalogFile(List<StudioFileRecord> catalogFiles, String selectedFileId) {
        List<StudioFileRecord> safe = catalogFiles == null ? List.of() : catalogFiles;
        if (selectedFileId == null || selectedFileId.isBlank()) {
            return safe;
        }
        List<StudioFileRecord> ordered = new ArrayList<>();
        safe.stream()
                .filter(file -> selectedFileId.equals(file.fileId()))
                .findFirst()
                .ifPresent(ordered::add);
        for (StudioFileRecord file : safe) {
            if (!selectedFileId.equals(file.fileId())) {
                ordered.add(file);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Ordering strategy: moves the active authoring session to the front of the
     * prompt preview while preserving existing history order for fallbacks.
     */
    private List<StudioSessionHistoryItem> prioritizeSessionHistory(
            List<StudioSessionHistoryItem> history,
            String selectedSessionId
    ) {
        List<StudioSessionHistoryItem> safe = history == null ? List.of() : history;
        if (selectedSessionId == null || selectedSessionId.isBlank()) {
            return safe;
        }
        List<StudioSessionHistoryItem> ordered = new ArrayList<>();
        safe.stream()
                .filter(item -> selectedSessionId.equals(item.sessionId()))
                .findFirst()
                .ifPresent(ordered::add);
        for (StudioSessionHistoryItem item : safe) {
            if (!selectedSessionId.equals(item.sessionId())) {
                ordered.add(item);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Context projector: records prompt-preview limits and omitted counts so
     * the agent can explicitly ask for tool hydration instead of assuming the
     * compact arrays are complete tenant state.
     */
    private Map<String, Object> authoringContextLimits(
            List<StudioAuthoringConversationMessage> conversation,
            List<StudioFileRecord> catalogFiles,
            List<StudioSessionHistoryItem> sessionHistory,
            List<StudioVisualCatalogEntry> catalogEntries,
            int conversationLimit,
            int catalogFilesLimit,
            int sessionHistoryLimit,
            int catalogPreviewLimit
    ) {
        int conversationSize = conversation == null ? 0 : conversation.size();
        int catalogFileSize = catalogFiles == null ? 0 : catalogFiles.size();
        int sessionHistorySize = sessionHistory == null ? 0 : sessionHistory.size();
        int catalogEntrySize = catalogEntries == null ? 0 : catalogEntries.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationLimit", conversationLimit);
        result.put("conversationTotal", conversationSize);
        result.put("conversationOmitted", Math.max(0, conversationSize - conversationLimit));
        result.put("catalogFilesLimit", catalogFilesLimit);
        result.put("catalogFilesTotal", catalogFileSize);
        result.put("catalogFilesOmitted", Math.max(0, catalogFileSize - catalogFilesLimit));
        result.put("sessionHistoryLimit", sessionHistoryLimit);
        result.put("sessionHistoryTotal", sessionHistorySize);
        result.put("sessionHistoryOmitted", Math.max(0, sessionHistorySize - sessionHistoryLimit));
        result.put("catalogPreviewLimit", catalogPreviewLimit);
        result.put("catalogPreviewTotal", catalogEntrySize);
        result.put("catalogPreviewOmitted", Math.max(0, catalogEntrySize - catalogPreviewLimit));
        return result;
    }

    /**
     * Context projector: resolves the session-bound catalog file row so Foundry
     * can distinguish latest-catalog defaults from explicit user choices.
     */
    private Map<String, Object> authoringCatalogFileMap(String tenantId, String catalogFileId) {
        String tenant = normalizeTenant(tenantId);
        if (catalogFileId == null || catalogFileId.isBlank()) {
            return Map.of();
        }
        return filesByTenant
                .getOrDefault(tenant, Map.of())
                .values()
                .stream()
                .filter(file -> catalogFileId.equals(file.fileId()))
                .findFirst()
                .map(this::authoringFileMap)
                .orElse(Map.of());
    }

    /**
     * Context projector: emits one tenant file registry row for the authoring
     * agent while preserving version and hash pins for follow-up tools.
     */
    private Map<String, Object> authoringFileMap(StudioFileRecord file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", file.fileId());
        result.put("tenantId", file.tenantId());
        result.put("logicalFileId", file.logicalFileId());
        result.put("version", file.version());
        result.put("fileName", file.fileName());
        result.put("fileTitle", file.fileTitle());
        result.put("filePath", file.filePath());
        result.put("fileType", file.fileType());
        result.put("mediaType", file.mediaType());
        result.put("sha256", file.sha256());
        result.put("createdAt", file.createdAt().toString());
        return result;
    }

    /**
     * Context projector: emits the lightweight identity fields a model needs to
     * render catalog-file choices without receiving local file paths or hashes.
     */
    private Map<String, Object> authoringFilePreviewMap(StudioFileRecord file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", file.fileId());
        result.put("logicalFileId", file.logicalFileId());
        result.put("version", file.version());
        result.put("fileName", file.fileName());
        result.put("fileTitle", file.fileTitle());
        result.put("fileType", file.fileType());
        result.put("createdAt", file.createdAt().toString());
        return result;
    }

    /**
     * Context projector: emits one historical authoring/draft session row so
     * the agent can ask whether to continue, fork, or start fresh.
     */
    private Map<String, Object> authoringSessionHistoryMap(StudioSessionHistoryItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", item.tenantId());
        result.put("assemblyId", item.assemblyId());
        result.put("sessionId", item.sessionId());
        result.put("displayName", item.displayName());
        result.put("sessionType", item.sessionType());
        result.put("status", item.status());
        result.put("baseCatalogHash", item.baseCatalogHash());
        result.put("catalogFileId", item.catalogFileId());
        result.put("createdAt", item.createdAt().toString());
        result.put("updatedAt", item.updatedAt().toString());
        result.put("lastOpenedAt", item.lastOpenedAt().toString());
        result.put("linkedFileCount", item.linkedFileCount());
        result.put("intentCount", item.intentCount());
        return result;
    }

    /**
     * Context projector: emits the lightweight history fields needed for
     * continue-or-fork questions without resending catalog hashes every turn.
     */
    private Map<String, Object> authoringSessionHistoryPreviewMap(StudioSessionHistoryItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", item.sessionId());
        result.put("displayName", item.displayName());
        result.put("sessionType", item.sessionType());
        result.put("status", item.status());
        result.put("catalogFileId", item.catalogFileId());
        result.put("lastOpenedAt", item.lastOpenedAt().toString());
        result.put("linkedFileCount", item.linkedFileCount());
        result.put("intentCount", item.intentCount());
        return result;
    }

    /**
     * Adapter: maps Foundry's advisory agent output into Studio's response
     * contract, canonicalizing proposal intent aliases against the selected
     * tenant catalog before running Fabric's normal grounding guard.
     */
    private StudioAuthoringConverseResponse mapAuthoringOutput(
            String tenant,
            String sessionId,
            Map<String, Object> output,
            List<StudioVisualCatalogEntry> selectedCatalogEntries
    ) {
        String kind = String.valueOf(output.getOrDefault("kind", "clarify"));
        String message = output.get("assistantMessage") == null ? "" : String.valueOf(output.get("assistantMessage"));
        switch (kind) {
            case "proposal" -> {
                Map<String, Object> proposalMap = asMap(output.get("proposal"));
                String needsYaml = proposalMap.get("needsYaml") == null ? "" : String.valueOf(proposalMap.get("needsYaml"));
                List<Map<String, Object>> intents = canonicalAuthoringProposalIntents(
                        selectedCatalogEntries,
                        asListOfMap(proposalMap.get("intents")));
                // Grounding guard: Fabric re-validates every proposed component against the admitted
                // catalog, regardless of what the agent/tools produced. An unadmitted component is a gap.
                for (Map<String, Object> intent : intents) {
                    String type = canonicalStudioIntentType(
                            intent.get("type") == null ? "ADD_COMPONENT" : String.valueOf(intent.get("type")));
                    String key = catalogEntryIntentKey(type);
                    Object entryId = key.isBlank() ? null : intent.get(key);
                    if (entryId == null) {
                        continue;
                    }
                    Optional<StudioIntentResponse> rejection = rejectIntentAgainstCatalog(
                            tenant, type, Map.of(key, String.valueOf(entryId)), selectedCatalogEntries);
                    if (rejection.isPresent()) {
                        // The gap names why, in the catalog rule's own words (not found, or carries no claim).
                        return StudioAuthoringConverseResponse.gap(sessionId,
                                "A proposed component is not admitted in this tenant's catalog: "
                                        + rejection.get().reason() + ": " + rejection.get().details(),
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
            case "execution" -> {
                return StudioAuthoringConverseResponse.gap(sessionId,
                        "Authoring agents cannot execute Flowfoundry runbook steps; Flow owns runbook DAG execution.",
                        List.of("flow-runbook-execution"));
            }
            default -> {
                List<StudioAuthoringQuestion> questions = new ArrayList<>();
                for (Map<String, Object> q : asListOfMap(output.get("questions"))) {
                    questions.add(new StudioAuthoringQuestion(
                            asString(q.get("id")),
                            asString(q.get("prompt")),
                            q.get("kind") == null ? "TEXT" : String.valueOf(q.get("kind")),
                            authoringQuestionOptions(q.get("options"))));
                }
                return StudioAuthoringConverseResponse.clarify(sessionId, message, questions);
            }
        }
    }

    /**
     * Adapter: rewrites model-emitted proposal intent aliases to exact
     * catalogEntryId values only when the selected catalog has a single
     * authoritative match. Unknown or ambiguous values are left untouched so
     * the grounding guard returns the normal catalog gap.
     */
    private List<Map<String, Object>> canonicalAuthoringProposalIntents(
            List<StudioVisualCatalogEntry> selectedCatalogEntries,
            List<Map<String, Object>> intents
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> intent : intents == null ? List.<Map<String, Object>>of() : intents) {
            Map<String, Object> canonical = new LinkedHashMap<>(intent);
            String type = canonicalStudioIntentType(
                    intent.get("type") == null ? "ADD_COMPONENT" : String.valueOf(intent.get("type")));
            if (!type.isBlank()) {
                canonical.put("type", type);
            }
            String key = catalogEntryIntentKey(type);
            String requested = key.isBlank() ? "" : answerAsString(intent.get(key));
            if (!requested.isBlank()) {
                canonicalCatalogEntryId(selectedCatalogEntries, requested)
                        .ifPresent(entryId -> canonical.put(key, entryId));
            }
            result.add(Map.copyOf(canonical));
        }
        return List.copyOf(result);
    }

    /**
     * Intent-key strategy: identifies the catalog entry payload field for the
     * Studio intent types that select components from the tenant catalog.
     */
    private static String catalogEntryIntentKey(String intentType) {
        String canonicalType = canonicalStudioIntentType(intentType);
        if ("ADD_COMPONENT".equals(canonicalType)) {
            return "catalogEntryId";
        }
        if ("REPLACE_COMPONENT".equals(canonicalType)) {
            return "newCatalogEntryId";
        }
        return "";
    }

    /**
     * Intent-type normalizer: maps model-friendly Studio intent spellings into
     * the canonical names consumed by the draft-intent log. Unknown intent
     * values are preserved so downstream validation can still reject them
     * explicitly when needed.
     */
    private static String canonicalStudioIntentType(String intentType) {
        String value = intentType == null ? "" : intentType.trim();
        if (value.isBlank()) {
            return "";
        }
        String token = aliasToken(value);
        return switch (token) {
            case "addcomponent", "componentadd", "addcatalogentry" -> "ADD_COMPONENT";
            case "replacecomponent", "componentreplace", "swapcomponent" -> "REPLACE_COMPONENT";
            case "removecomponent", "componentremove", "deletecomponent" -> "REMOVE_COMPONENT";
            case "connect", "connectports", "addconnection" -> "CONNECT";
            case "disconnect", "disconnectports", "removeconnection" -> "DISCONNECT";
            default -> value;
        };
    }

    /**
     * Catalog alias resolver: maps a display label or uploaded artifact basename
     * back to the exact selected-catalog id only when there is exactly one match.
     */
    private Optional<String> canonicalCatalogEntryId(
            List<StudioVisualCatalogEntry> selectedCatalogEntries,
            String requested
    ) {
        String candidate = requested == null ? "" : requested.trim();
        if (candidate.isBlank()) {
            return Optional.empty();
        }
        List<StudioVisualCatalogEntry> entries = selectedCatalogEntries == null
                ? List.of()
                : selectedCatalogEntries;
        Optional<String> exact = entries.stream()
                .map(StudioVisualCatalogEntry::catalogEntryId)
                .filter(candidate::equals)
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<String> matches = entries.stream()
                .filter(entry -> catalogEntryAliasMatches(entry, candidate))
                .map(StudioVisualCatalogEntry::catalogEntryId)
                .distinct()
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /**
     * Alias matcher: compares a model-emitted value against safe, deterministic
     * aliases derived from the catalog entry id and Fabric display label.
     */
    private boolean catalogEntryAliasMatches(StudioVisualCatalogEntry entry, String candidate) {
        if (entry == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        String id = entry.catalogEntryId();
        List<String> aliases = new ArrayList<>();
        aliases.add(id);
        aliases.add(labelForCatalogEntry(id));
        String uploaded = id.startsWith("uploaded:") ? id.substring("uploaded:".length()) : "";
        if (!uploaded.isBlank()) {
            aliases.add(uploaded);
            if (uploaded.endsWith(".jar")) {
                aliases.add(uploaded.substring(0, uploaded.length() - ".jar".length()));
            }
        }
        return aliases.stream().anyMatch(alias -> aliasEquals(candidate, alias));
    }

    /**
     * Alias equality helper: allows punctuation/case differences in UI labels
     * while keeping the comparison deterministic and catalog-derived.
     */
    private static boolean aliasEquals(String left, String right) {
        if (left == null || right == null || right.isBlank()) {
            return false;
        }
        return left.equalsIgnoreCase(right) || aliasToken(left).equals(aliasToken(right));
    }

    /**
     * Alias token normalizer: removes punctuation and casing so display labels
     * like "Unfurl Fabric Advisor Gemini 0 1 0 SNAPSHOT Jar" can resolve to
     * their exact uploaded artifact id when unique.
     */
    private static String aliasToken(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /**
     * JSON adapter: accepts both the current label/value option object shape and
     * older string-only model/tool outputs without leaking Java map formatting to
     * the Studio UI.
     */
    private static List<StudioAuthoringQuestionOption> authoringQuestionOptions(Object value) {
        List<StudioAuthoringQuestionOption> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String label = firstNonBlank(
                            asString(map.get("label")),
                            asString(map.get("name")),
                            asString(map.get("text")),
                            asString(map.get("prompt")),
                            asString(map.get("value")));
                    String optionValue = firstNonBlank(
                            asString(map.get("value")),
                            asString(map.get("id")),
                            asString(map.get("catalogEntryId")),
                            asString(map.get("fileId")),
                            label);
                    result.add(new StudioAuthoringQuestionOption(label, optionValue));
                } else {
                    result.add(StudioAuthoringQuestionOption.of(asString(item)));
                }
            }
        }
        return List.copyOf(result);
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
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        return new StudioAssemblyListResponse(tenant, assemblies.values().stream()
                .sorted(Comparator.comparing(StudioAssemblySummary::assemblyId))
                .toList());
    }

    /**
     * Read-model projector: builds a catalog-scoped Dynamic DCP view for
     * catalog browsing before a governed draft session exists.
     */
    public StudioDynamicDcpProjection dynamicDcpProjection(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
        return dynamicDcpProjectionForEntries(tenant, assembly, entries, List.of());
    }

    /**
     * Read-model projector: builds a draft-session Dynamic DCP view by replaying
     * the accepted membership intents. This mirrors compile's inventory source
     * so the UI inspects the same component set that Step 12 will compile.
     */
    public StudioDynamicDcpProjection dynamicDcpProjection(String tenantId, String assemblyId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return dynamicDcpProjection(tenantId, assemblyId);
        }
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        // The projection draws what the catalog still has and names what it no longer has, so the operator can see the
        // draft and remove those components (LLD-fabric-studio-api, Tenant Catalog and Assets).
        DraftEntries draft = draftEntries(session);
        List<String> warnings = new ArrayList<>();
        if (draft.present().isEmpty() && draft.missing().isEmpty()) {
            warnings.add("draft session contains no selected catalog components");
        }
        for (String missing : draft.missing()) {
            warnings.add(missingEntryWarning(session.tenantId(), missing));
        }
        return dynamicDcpProjectionForEntries(session.tenantId(), session.assemblyId(), draft.present(), warnings);
    }

    /**
     * Adapter: turns an already-selected catalog entry list into the aggregate DCP
     * claim graph consumed by {@link DcpProjectionProjector}.
     */
    private StudioDynamicDcpProjection dynamicDcpProjectionForEntries(
            String tenant,
            String assembly,
            List<StudioVisualCatalogEntry> selectedEntries,
            List<String> sourceWarnings
    ) {
        List<StudioVisualCatalogEntry> entries = selectedEntries == null ? List.of() : List.copyOf(selectedEntries);
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        // An unrecorded assembly's target application is named after the assembly (StudioAssemblySummary); nothing is
        // invented for it (LLD-fabric-studio-api, Assembly records).
        StudioAssemblySummary summary = assemblies.getOrDefault(assembly, defaultAssemblySummary(tenant, assembly));
        String target = summary.targetApplicationName();
        String rootNodeId = "company:" + slug(target);
        String focusNodeId = "assembly:" + slug(assembly);
        Map<URI, StudioVisualCatalogEntry> entryByClaimUri = entries.stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> claimUriForEntry(entry.catalogEntryId()),
                        entry -> entry,
                        (left, right) -> left,
                        LinkedHashMap::new));
        URI rootClaimUri = URI.create("urn:unfurl:studio:" + rootNodeId);
        URI assemblyClaimUri = URI.create("urn:unfurl:studio:" + focusNodeId);
        List<String> warnings = new ArrayList<>(sourceWarnings == null ? List.of() : sourceWarnings);
        Map<String, List<URI>> declaredChildren = declaredChildren(tenant, entries, warnings);
        Set<URI> nestedEntryClaimUris = new LinkedHashSet<>();
        entries.forEach(entry -> nestedEntryClaimUris.addAll(childClaimUrisForEntry(entry, declaredChildren)));
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
                .forEach(entry -> claims.put(claimUriForEntry(entry.catalogEntryId()), claimForEntry(entry, declaredChildren)));

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
                entries,
                warnings);
    }

    /**
     * An aggregate's declared children (DCP HLD-C2 A.14, {@code child_claims}) among the selected entries, by entry id, as
     * the projection's per-entry claim URIs. A child is the selected entry whose own claim's {@code identity.uri} is the
     * {@code claim_uri}; each entry's claim is read as compile reads it, never from its catalog id. A required child that
     * no selected entry provides is named in {@code warnings}; an absent optional child is not, because the aggregate runs
     * without it (LLD-fabric-studio-api, Aggregate children).
     */
    private Map<String, List<URI>> declaredChildren(String tenant, List<StudioVisualCatalogEntry> entries, List<String> warnings) {
        Map<URI, URI> entryClaimUriByIdentity = new LinkedHashMap<>();
        Map<String, com.unfurl.dcp.claim.Claim> claims = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry entry : entries) {
            groundedClaim(tenant, entry.catalogEntryId()).ifPresent(claim -> {
                claims.put(entry.catalogEntryId(), claim);
                if (claim.identity() != null && claim.identity().uri() != null) {
                    entryClaimUriByIdentity.putIfAbsent(claim.identity().uri(), claimUriForEntry(entry.catalogEntryId()));
                }
            });
        }
        Map<String, List<URI>> children = new LinkedHashMap<>();
        claims.forEach((entryId, claim) -> {
            List<URI> present = new ArrayList<>();
            for (com.unfurl.dcp.claim.ChildClaim child : claim.childClaims()) {
                URI entryClaimUri = child.claimUri() == null ? null : entryClaimUriByIdentity.get(child.claimUri());
                if (entryClaimUri != null) {
                    present.add(entryClaimUri);
                } else if (child.isRequired()) {
                    warnings.add("CHILD_CLAIM_MISSING: " + labelForCatalogEntry(entryId) + " requires child " + child.claimUri()
                            + ", which this draft does not include");
                }
            }
            if (!present.isEmpty()) children.put(entryId, List.copyOf(present));
        });
        // The projection never nests along a cycle (A names B, B names A): those references are dropped, so the cycle's
        // members stay under the assembly, visible, and the warning names them. Resolution refuses the draft.
        Map<URI, List<URI>> references = new LinkedHashMap<>();
        Map<URI, String> entryIdByClaimUri = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry entry : entries) {
            URI entryClaimUri = claimUriForEntry(entry.catalogEntryId());
            entryIdByClaimUri.put(entryClaimUri, entry.catalogEntryId());
            references.put(entryClaimUri, children.getOrDefault(entry.catalogEntryId(), List.of()));
        }
        StronglyConnectedGroups<URI> groups = StronglyConnectedGroups.of(references);
        for (List<URI> cycle : groups.cycles(references)) {
            warnings.add("CHILD_CLAIM_CYCLE: " + cycle.stream().map(uri -> labelForCatalogEntry(entryIdByClaimUri.get(uri)))
                    .sorted().collect(java.util.stream.Collectors.joining(", ")) + " name each other as children");
        }
        Map<String, List<URI>> acyclic = new LinkedHashMap<>();
        children.forEach((entryId, entryChildren) -> {
            URI parent = claimUriForEntry(entryId);
            List<URI> kept = entryChildren.stream().filter(child -> !groups.sameGroup(parent, child)).toList();
            if (!kept.isEmpty()) acyclic.put(entryId, kept);
        });
        return acyclic;
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
                List.of(),
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
            List<StudioVisualCatalogEntry> entries,
            List<String> sourceWarnings
    ) {
        List<StudioDynamicDcpNode> nodes = dcpProjection.nodes().stream()
                .map(node -> studioNodeFromDcp(node, nodeIdsByClaimUri, entryByClaimUri))
                .toList();
        List<StudioDynamicDcpNode> childNodes = nodes.stream()
                .filter(StudioDynamicDcpNode::replacementAllowed)
                .toList();
        List<StudioDynamicDcpEdge> declaredEdges = dcpProjection.edges().stream()
                .map(edge -> studioEdgeFromDcp(edge, nodeIdsByClaimUri))
                .toList();

        List<StudioSubstratePort> substratePorts = deriveSubstratePorts(childNodes, entries);
        List<StudioPortConnectionEdge> connections = derivePortConnections(childNodes, entries, substratePorts);
        // The CONTAINS tree follows the matched bindings where no containment is declared (LLD-fabric-studio-api,
        // Containment derived from bindings). No other edge is invented between components: a dependency is its
        // connection.
        StudioDerivedContainment.Result derived = StudioDerivedContainment.apply(nodes,
                declaredEdges, connections, focusNodeId);
        nodes = derived.nodes();
        List<StudioDynamicDcpEdge> edges = derived.edges();
        List<String> warnings = new ArrayList<>();
        if (sourceWarnings != null) {
            warnings.addAll(sourceWarnings);
        }
        warnings.addAll(dcpProjection.warnings());

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
                warnings);
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
     * Projector: builds the recursive-projection view node for one catalog entry, named under Studio's own
     * {@code urn:unfurl:studio:} URI, with the entry's raw dependencies so the tree can draw its edges. It is a view
     * for the navigator only (DCP HLD-C §3.10): it is never admitted, compiled, exported or reported as the
     * component's claim; compile uses {@link #catalogEntryForVisual}.
     */
    private Claim claimForEntry(StudioVisualCatalogEntry entry, Map<String, List<URI>> declaredChildren) {
        Map<String, Object> dynamic = entry.dynamicComposition();
        return claim(
                claimUriForEntry(entry.catalogEntryId()),
                labelForCatalogEntry(entry.catalogEntryId()),
                ComponentKind.COMPONENT,
                stringValue(dynamic.get("dcpType"), "COMPONENT"),
                stringValue(dynamic.get("level"), "CHILD"),
                childClaimUrisForEntry(entry, declaredChildren),
                capabilitiesFromVisual(entry.visualManifest()),
                rawNeedsForEntry(entry));
    }

    /**
     * Builder: assembles one recursive-projection view node (aggregate, assembly or entry) in the DCP claim shape the
     * projector reads. View nodes stay inside the projection read model and never stand in for a component's claim.
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
                reconstructedDomain(label),
                List.of(new Refusal(
                        "unmodelled-runtime-concerns",
                        "runtime-only concerns remain owned by the deployment environment",
                        "urn:unfurl:deployment-owner")),
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
                new ConflictResolution(List.of(), List.of("prefer admitted source claims when available"), false),
                null,
                new IntegrationPorts(Map.of()),
                com.unfurl.dcp.fault.FaultPolicy.empty(),
                new ClaimMetadata("0.2.0", "1.0.0", Instant.EPOCH, extensions));
    }

    /**
     * DCP fallback-domain builder: provides the required claim boundary text when Studio has to
     * reconstruct a catalog entry from persisted visual metadata after a catalog-map reload.
     */
    private DomainAssertion reconstructedDomain(String label) {
        return new DomainAssertion(
                "reconstructed Studio catalog claim for " + label,
                List.of(new Concern(
                        "runtime-capabilities",
                        "capability offers and dependencies reconstructed from admitted Studio visual metadata",
                        "source catalog claims remain preferred whenever available",
                        List.of(),
                        List.of())),
                List.of(
                        "reconstruction preserves DCP capability and dependency boundaries",
                        "deployment-owned concerns stay outside the reconstructed component claim"));
    }

    /**
     * Projection helper: returns raw DCP dependency strings preserved from catalog
     * manifests/admitted claims.
     */
    private List<String> rawNeedsForEntry(StudioVisualCatalogEntry entry) {
        return stringList(entry.dynamicComposition().get("rawNeeds"));
    }

    /**
     * An entry's children for the projection: the children its claim declares ({@code child_claims}, already matched to
     * the selected entries), then any nesting references its presentation metadata carries.
     */
    private List<URI> childClaimUrisForEntry(StudioVisualCatalogEntry entry, Map<String, List<URI>> declaredChildren) {
        Map<String, Object> dynamic = entry.dynamicComposition();
        LinkedHashSet<URI> result = new LinkedHashSet<>(declaredChildren.getOrDefault(entry.catalogEntryId(), List.of()));
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
            // Owner qualifiers mean the same in both lists, so external optional dependencies are skipped too.
            List<String> rawDependencies = new ArrayList<>(rawDependencyStrings(entry));
            rawDependencies.addAll(rawOptionalDependencyStrings(entry));
            rawNeedsByNodeId.put(node.nodeId(), rawDependencies);
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
                    String relation = need.optional() ? " optionally used by " : " required by ";
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
                                    + relation
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
                                            + relation
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
                // Optional dependencies are never substrate requirements (HLD-unfurl-fabric, Core Flow step 3).
                if (need.optional() || offeredCapabilities.contains(need.capability())
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
     * Reader: the entry's raw {@code claim.dependencies.optional} strings from the dynamic-composition block's
     * {@code rawOptionalNeeds}, or an empty list when it declares none.
     */
    private List<String> rawOptionalDependencyStrings(StudioVisualCatalogEntry entry) {
        Map<String, Object> dynamicComposition = entry.dynamicComposition();
        if (dynamicComposition == null || !(dynamicComposition.get("rawOptionalNeeds") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
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
        if (componentNodeId == null || componentNodeId.isBlank()) {
            throw new IllegalArgumentException("COMPONENT_NODE_REQUIRED: replacement candidates are asked for one component; "
                    + "name it with componentNodeId");
        }
        StudioDynamicDcpProjection projection = dynamicDcpProjection(tenantId, assemblyId);
        String selectedNodeId = componentNodeId;
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

        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
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
            out.add(new PortDescriptor(id, capability, Boolean.TRUE.equals(port.get("optional"))));
        }
        return out;
    }

    /**
     * Value Object: one visual-manifest port.
     *
     * @param id         the port id.
     * @param capability the capability it offers or depends on.
     * @param optional   true for a DEPENDENCY port from {@code claim.dependencies.optional}, which may stay unconnected.
     */
    private record PortDescriptor(String id, String capability, boolean optional) {
    }

    public StudioAssemblySummary createAssembly(String tenantId, StudioCreateAssemblyRequest request) {
        String tenant = normalizeTenant(tenantId);
        if (request == null) {
            throw new IllegalArgumentException("assembly request is required");
        }
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        StudioAssemblySummary summary = new StudioAssemblySummary(
                tenant,
                request.assemblyId(),
                request.targetApplicationName(),
                request.defaultDeploymentTarget(),
                "",
                "",
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
                ? new StudioSaveDraftRequest("", "", "", "", "", 0)
                : request;
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
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
     * validity remains governed by later Fabric DCP operations. It holds the
     * service lock, so the summary, layout and sessions it captures are one
     * consistent state, never one taken mid-import.
     */
    public synchronized StudioAssemblySnapshot saveAssemblySnapshot(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = normalizeAssembly(assemblyId);
        StudioAssemblySummary summary = assembliesByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
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
     * Snapshot loader (Unit of Work): restores a portable assembly snapshot into the addressed tenant and assembly, all
     * or nothing (LLD-fabric-studio-api, Assembly snapshots). Route ids are authoritative.
     *
     * <p>Phase 1 validates and normalizes the whole snapshot (summary, layout rules, session ids) and reads, but never
     * writes, stored state; any violation is refused before anything changes. Phase 2 ({@link #commitAssemblySnapshot})
     * replaces the summary, layout and the assembly's sessions and persists, restoring the previous records if
     * persisting fails. The method holds the service lock that session intents, compile and layout writes hold, so
     * no concurrent operation observes a mix of old and new records.
     *
     * @throws IllegalArgumentException {@value StudioLayoutRules#INVALID} or {@value #ASSEMBLY_SNAPSHOT_INVALID}, naming
     *         the field, with stored state unchanged
     */
    public synchronized StudioAssemblySnapshot loadAssemblySnapshot(
            String tenantId,
            String assemblyId,
            StudioAssemblySnapshot snapshot
    ) {
        String tenant = normalizeTenant(tenantId);
        String assembly = normalizeAssembly(assemblyId);
        if (snapshot == null) {
            throw new IllegalArgumentException("assembly snapshot is required");
        }
        // Phase 1: validate and normalize everything; stored state is only read.
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

        StudioLayoutState sourceLayout = snapshot.layout() == null
                ? storedLayoutOrInitial(tenant, assembly)
                : snapshot.layout();
        // A supplied snapshot's layout follows the same rules as a write (LLD-fabric-studio-api, Layout state extension).
        StudioLayoutRules.requireStored(sourceLayout);
        StudioLayoutState normalizedLayout = sourceLayout.scopedTo(tenant, assembly);

        requireImportableSessions(snapshot.sessions());
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
                        session.intentLog(),
                        session.catalogFileId(),
                        session.displayName(),
                        session.sessionType(),
                        session.status(),
                        session.createdAt(),
                        session.updatedAt(),
                        session.lastOpenedAt(),
                        // Compile records are facts of the Studio that compiled: nothing in the compiled bytes proves
                        // which revision or policy they came from, so a snapshot never carries them in and the imported
                        // draft is compiled again here (LLD-fabric-studio-api, Compile records and freshness).
                        List.of()))
                .sorted(Comparator.comparing(StudioDraftSession::sessionId))
                .toList();

        // Phase 2: replace the summary, layout and sessions together, and persist.
        commitAssemblySnapshot(tenant, assembly, normalizedAssembly, normalizedLayout, normalizedSessions);
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

    /**
     * Guard: every imported session has a non-blank id and no id appears twice, so phase 2 can key every session and
     * none silently overwrites another.
     *
     * @throws IllegalArgumentException {@value #ASSEMBLY_SNAPSHOT_INVALID} naming the session index
     */
    private static void requireImportableSessions(List<StudioDraftSession> sessions) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < sessions.size(); index++) {
            String sessionId = sessions.get(index).sessionId();
            if (sessionId.isBlank()) {
                throw new IllegalArgumentException(ASSEMBLY_SNAPSHOT_INVALID + ": sessions[" + index + "].sessionId is required");
            }
            if (!seen.add(sessionId)) {
                throw new IllegalArgumentException(ASSEMBLY_SNAPSHOT_INVALID + ": sessions[" + index + "].sessionId "
                        + sessionId + " appears twice");
            }
        }
    }

    /**
     * Unit of Work commit for {@link #loadAssemblySnapshot}: replaces the assembly's summary, layout and sessions with
     * already-validated records, then persists. If persisting fails, the previous records are restored in memory and
     * the failure is rethrown, so the import is never partly applied. Called with the service lock held.
     */
    private void commitAssemblySnapshot(
            String tenant,
            String assembly,
            StudioAssemblySummary summary,
            StudioLayoutState layoutState,
            List<StudioDraftSession> sessions
    ) {
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        Map<String, StudioLayoutState> layouts = layoutsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        Map<String, StudioDraftSession> tenantSessions = sessionsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        String prefix = assembly + "/";
        StudioAssemblySummary previousSummary = assemblies.get(assembly);
        StudioLayoutState previousLayout = layouts.get(assembly);
        Map<String, StudioDraftSession> previousSessions = new LinkedHashMap<>();
        tenantSessions.forEach((key, session) -> {
            if (key.startsWith(prefix)) previousSessions.put(key, session);
        });
        try {
            assemblies.put(assembly, summary);
            layouts.put(assembly, layoutState);
            tenantSessions.keySet().removeIf(key -> key.startsWith(prefix));
            sessions.forEach(session -> tenantSessions.put(sessionKey(assembly, session.sessionId()), session));
            persist();
        } catch (RuntimeException failure) {
            restore(assemblies, assembly, previousSummary);
            restore(layouts, assembly, previousLayout);
            tenantSessions.keySet().removeIf(key -> key.startsWith(prefix));
            tenantSessions.putAll(previousSessions);
            throw failure;
        }
    }

    /** Puts back a previous value, or removes the key when there was none. */
    private static <V> void restore(Map<String, V> map, String key, V previous) {
        if (previous == null) {
            map.remove(key);
        } else {
            map.put(key, previous);
        }
    }

    /** The assembly's stored layout, or its initial layout, without storing anything (a read for validation). */
    private StudioLayoutState storedLayoutOrInitial(String tenant, String assembly) {
        StudioLayoutState stored = layoutsByTenant.getOrDefault(tenant, Map.of()).get(assembly);
        return stored == null ? StudioLayoutState.initial(tenant, assembly) : stored;
    }

    public StudioLayoutState layout(String tenantId, String assemblyId) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        Map<String, StudioLayoutState> layouts = layoutsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        return layouts.computeIfAbsent(assembly, ignored -> StudioLayoutState.initial(tenant, assembly));
    }

    /**
     * Writes the assembly's layout (workbench view state) after {@link StudioLayoutRules#requireWritable} accepts it.
     * It holds the service lock, so a layout write never interleaves with a snapshot import's commit or rollback.
     *
     * @throws IllegalArgumentException {@value StudioLayoutRules#INVALID} naming the field
     */
    public synchronized StudioLayoutState saveLayout(String tenantId, String assemblyId, StudioLayoutStateRequest request) {
        String tenant = normalizeTenant(tenantId);
        String assembly = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        // Every write carries a valid workbench view state; nothing is defaulted (LLD-fabric-studio-api).
        StudioLayoutRules.requireWritable(request);
        StudioLayoutState state = new StudioLayoutState(
                tenant,
                assembly,
                null,
                request.semanticZoomLevel(),
                request.selectedSurface(),
                request.camera(),
                request.annotations(),
                request.activeViewpoint(),
                request.activeOverlays(),
                request.colourOverlay(),
                request.renderer(),
                request.layout());
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
        StudioFileRecord selectedCatalog = selectedCatalogFile(tenant, safe.catalogFileId());
        StudioCatalogSnapshot selectedSnapshot = selectedCatalog == null
                ? null
                : catalogSnapshotForFile(tenant, selectedCatalog.fileId());
        String resolvedCatalogHash = selectedSnapshot == null
                ? response(entriesByTenant.computeIfAbsent(tenant, this::catalogEntries)).catalogHash()
                : selectedSnapshot.catalogHash();
        Instant now = Instant.now();
        String sessionId = "studio-session-" + UUID.randomUUID();
        StudioDraftSession session = new StudioDraftSession(
                tenant,
                assembly,
                sessionId,
                resolvedCatalogHash,
                "DYNAMIC",
                safe.needsId(),
                safe.trustPolicyId(),
                safe.initialCandidateId(),
                0,
                List.of(),
                List.of(collaborator(safe.collaboratorId(), safe.collaboratorName(), "")),
                List.of(),
                selectedCatalog == null ? "" : selectedCatalog.fileId(),
                draftDisplayName(safe.displayName(), assembly, selectedCatalog),
                "DRAFT",
                "OPEN",
                now,
                now,
                now,
                List.of());
        sessionsByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(sessionKey(assembly, sessionId), session);
        // Starting a draft records its assembly, keeping what an earlier record chose (LLD-fabric-studio-api, Assembly
        // records).
        updateAssemblyRevision(session);
        if (selectedCatalog != null) {
            linkFileToSession(tenant, sessionId, "", selectedCatalog.fileId(), "INPUT");
        }
        persist();
        publishSessionEvent(session);
        StudioCreateDraftCompositionResponse response = new StudioCreateDraftCompositionResponse(session);
        StudioExportArtifact diagnostic = diagnosticArtifact(tenant, "draft-session", "draft-session.json", response);
        registerArtifactFile(session, diagnostic, "DRAFT", "OUTPUT");
        persist();
        return new StudioCreateDraftCompositionResponse(
                session,
                List.of(diagnostic));
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
        Instant now = Instant.now();
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
                intentLog,
                current.catalogFileId(),
                current.displayName(),
                current.sessionType(),
                "OPEN",
                current.createdAt(),
                now,
                now,
                current.compileRecords());
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
                current.intentLog(),
                current.catalogFileId(),
                current.displayName(),
                current.sessionType(),
                current.status(),
                current.createdAt(),
                current.updatedAt(),
                Instant.now(),
                current.compileRecords());
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
            // Session mode applies no trust classification (LLD gap: a session's trust policy is not applied), so a
            // session NO_MATCH carries conflicts and no trust rejections.
            return StudioDeploymentResolveResponse.invalid(
                    build.invalid().reason(),
                    build.invalid().details(),
                    List.of(),
                    build.invalid().reasons(),
                    List.of());
        }
        StudioDeploymentResolveResponse response = deploymentService.resolveDeployment(build.candidate(), request);
        // The resolver names a selection by artifact coordinates; Studio's projection names the draft's components by
        // catalog entry, so each selection also says which entry it was resolved for (LLD-fabric-studio-api,
        // Deployment Resolution).
        Map<String, String> entryIds = build.catalogEntryIdsByCoordinates();
        return response.withSelections(response.selections().stream()
                .map(selection -> selection.withCatalogEntryId(entryIds.getOrDefault(selection.componentId(), "")))
                .toList());
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
        List<StudioExportArtifact> supportArtifacts = new ArrayList<>();
        List<StudioExportArtifact> diagnosticArtifacts = new ArrayList<>();
        StudioExportArtifact signed = null;
        byte[] signedEnvelopeBytes = null;
        Optional<FabricContractSigner> compileSigner = Optional.empty();
        if (request != null && request.sign()) {
            compileSigner = studioSigner();
            SignedArtifact signedRoot = signRootContract(compiled.contract().contract(), compileSigner);
            if (signedRoot.invalid() != null) {
                warnings.add(signedRoot.invalid().details());
            } else {
                signed = exportArtifact(
                        session.tenantId(),
                        "signed-contract-" + session.sessionId(),
                        "signed-contract.json",
                        "application/json",
                        signedRoot.bytes());
                SignedArtifact signedEnvelope = signCompiledContract(compiled.contract(), compileSigner);
                if (signedEnvelope.invalid() != null) {
                    warnings.add(signedEnvelope.invalid().details());
                } else {
                    signedEnvelopeBytes = signedEnvelope.bytes();
                    supportArtifacts.add(exportArtifact(
                            session.tenantId(),
                            "signed-compiled-contract-" + session.sessionId(),
                            "signed-compiled-contract.yaml",
                            "application/yaml",
                            signedEnvelope.bytes()));
                }
                supportArtifacts.add(exportArtifact(
                        session.tenantId(),
                        "dcp-runtime-bundle-" + session.sessionId(),
                        "dcp-runtime-bundle.zip",
                        "application/zip",
                        dcpRuntimeBundle(compiled.contract(), build.candidate(), compileSigner.orElseThrow())));
            }
        }
        StudioExportArtifact contract = exportArtifact(
                session.tenantId(),
                "contract-" + session.sessionId(),
                "contract.yaml",
                "application/yaml",
                compiled.rootContractBytes());
        StudioExportArtifact profile = exportArtifact(
                session.tenantId(),
                "substrate-profile-" + session.sessionId(),
                "substrate-profile.yaml",
                "application/yaml",
                compiled.profileBytes());
        diagnosticArtifacts.add(diagnosticFileArtifact(
                session.tenantId(),
                "compiled-contract-envelope-" + session.sessionId(),
                "compiled-contract-envelope.yaml",
                "application/yaml",
                compiled.compiledEnvelopeBytes()));
        List<Map<String, Object>> hostedAudit = hostedComponentsAudit(compiled.contract());
        if (!hostedAudit.isEmpty()) {
            diagnosticArtifacts.add(diagnosticFileArtifact(
                    session.tenantId(),
                    "hosted-components-" + session.sessionId(),
                    "hosted-components.json",
                    "application/json",
                    jsonBytes(hostedAudit)));
        }
        // The compile record (LLD-fabric-studio-api, Compile records and freshness). Its envelopes go to the
        // content-addressed store first, so a record never names bytes that were not stored.
        StudioCompileRecord record = new StudioCompileRecord(
                session.sceneRevision(),
                freshness.policyHash(request == null ? null : request.deploymentPolicy()),
                contractHash(compiled.contract()),
                compiledStore.put(session.tenantId(), compiled.compiledEnvelopeBytes()),
                compiledStore.put(session.tenantId(), compiled.profileBytes()),
                signedEnvelopeBytes == null ? null : compiledStore.put(session.tenantId(), signedEnvelopeBytes),
                Instant.now());
        StudioCompileDraftCandidateResponse responseSnapshot = new StudioCompileDraftCandidateResponse(
                "COMPILED",
                build.candidate().candidateId(),
                contract,
                profile,
                signed,
                compileWarnings(session, warnings),
                "",
                "",
                session.sceneRevision(),
                expected,
                supportArtifacts,
                diagnosticArtifacts,
                List.of(),
                List.of(),
                record);
        diagnosticArtifacts.add(diagnosticArtifact(
                session.tenantId(),
                "compile-response",
                "compile-response.json",
                responseSnapshot));
        registerCompileFiles(session, contract, profile, signed, supportArtifacts, diagnosticArtifacts);
        StudioDraftSession compiledSession = session.withCompileRecord(record, "COMPILED", record.compiledAt());
        putSession(compiledSession);
        publishSessionEvent(compiledSession);
        persist();
        return new StudioCompileDraftCandidateResponse(
                responseSnapshot.status(),
                responseSnapshot.candidateId(),
                responseSnapshot.contractArtifact(),
                responseSnapshot.substrateProfileArtifact(),
                responseSnapshot.signedContractArtifact(),
                responseSnapshot.warnings(),
                responseSnapshot.reason(),
                responseSnapshot.details(),
                responseSnapshot.expectedRevision(),
                responseSnapshot.receivedRevision(),
                supportArtifacts,
                diagnosticArtifacts,
                List.of(),
                List.of(),
                record);
    }

    /**
     * The compiled contract's canonical hash: SHA-256 of {@code CompiledContractCodec.canonicalBytes}, the bytes Fabric's
     * signer signs, in the signer's form (lower-case hex without a prefix), so a signed envelope's {@code canonicalHash}
     * equals it and the reader can prove a record against its bytes.
     */
    private String contractHash(CompiledContract compiled) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(new CompiledContractCodec().canonicalBytes(compiled)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Read model: compares two compile records of a session (LLD-fabric-studio-api, <i>Contract diff and
     * verification</i>). The base is the latest record at {@code baseRevision}; the target is the current record, or
     * the latest at {@code revision}. Against the current record the freshness rule applies, so a diff is never
     * returned for a draft or policy that has moved on. Never changes the session.
     *
     * <p>Pattern: <b>Facade</b> over {@link StudioCompileFreshness}, {@link StudioCompiledContractReader} and
     * {@link ContractDiffer}, the one comparison {@code fabric diff} also prints.
     *
     * @param tenantId      the tenant.
     * @param assemblyId    the assembly.
     * @param sessionId     the draft session.
     * @param baseRevision  the revision of the base record.
     * @param revision      the revision of the target record, or null for the current record.
     * @param requestPolicy the policy the request would compile with now, if it names one.
     * @return the diff, or why there is none.
     */
    public synchronized StudioContractDiff contractDiff(String tenantId, String assemblyId, String sessionId,
                                                        long baseRevision, Long revision,
                                                        Optional<StudioDeploymentPolicyDraft> requestPolicy) {
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        Optional<StudioCompileRecord> current = freshness.currentRecord(session);
        if (current.isEmpty()) {
            StudioCompileFreshness.Verdict verdict = freshness.of(session, null, requestPolicy);
            return diffResponse(session, new StudioContractDiff(false,
                    new StudioAbsentReason(StudioAbsentReason.COMPILE_REQUIRED, verdict.message()),
                    null, null, false, null, List.of()));
        }
        Optional<StudioCompileRecord> base = latestRecordAt(session, baseRevision);
        if (base.isEmpty()) {
            return diffResponse(session, new StudioContractDiff(false, noRecordAt(session, baseRevision),
                    null, null, false, null, List.of()));
        }
        StudioCompileRecord target;
        if (revision == null) {
            target = current.get();
        } else {
            Optional<StudioCompileRecord> named = latestRecordAt(session, revision);
            if (named.isEmpty()) {
                return diffResponse(session, new StudioContractDiff(false, noRecordAt(session, revision),
                        StudioCompileProvenance.of(base.get()), null, true, null, List.of()));
            }
            target = named.get();
        }
        boolean historical = !target.equals(current.get());
        if (!historical) {
            StudioCompileFreshness.Verdict verdict = freshness.of(session, target, requestPolicy);
            if (!verdict.current()) {
                return diffResponse(session, new StudioContractDiff(false,
                        new StudioAbsentReason(verdict.state().name(), verdict.message()),
                        StudioCompileProvenance.of(base.get()), StudioCompileProvenance.of(target), false, null,
                        List.of()));
            }
        }
        ContractDiff diff;
        try {
            // Both records are proved against their bytes first, so the diff compares the contracts it names.
            StudioCompiledContractReader.Loaded from = compiledReader.load(session.tenantId(), base.get(), false);
            StudioCompiledContractReader.Loaded to = compiledReader.load(session.tenantId(), target, false);
            diff = new ContractDiffer().diff(base.get().contractHash(), from.compiled(), target.contractHash(),
                    to.compiled(), from.profile(), to.profile());
        } catch (StudioCompiledContractReader.Unavailable | StudioCompiledContractReader.Inconsistent ex) {
            return diffResponse(session, new StudioContractDiff(false, recordProblem(ex),
                    StudioCompileProvenance.of(base.get()), StudioCompileProvenance.of(target), historical, null,
                    List.of()));
        }
        return diffResponse(session, new StudioContractDiff(true, null, StudioCompileProvenance.of(base.get()),
                StudioCompileProvenance.of(target), historical, diff, List.of()));
    }

    /**
     * Read model: verifies a compile record's signed envelope with Studio's trust keys, and checks its selections
     * against the tenant's admitted catalog (LLD-fabric-studio-api, <i>Contract diff and verification</i>). With nothing
     * named it verifies the current record, under the freshness rule; a named past record is historical. An unsigned
     * compile is {@code NOT_SIGNED}, and without a configured trust-key directory the answer is
     * {@code TRUST_KEYS_UNAVAILABLE}: the signing key is never used to verify its own signature. Never changes the
     * session.
     *
     * <p>Pattern: <b>Facade</b> over {@link StudioCompileFreshness}, {@link StudioCompiledContractReader},
     * {@link SignatureVerifier} and {@link CatalogDriftChecker}, the checks {@code fabric verify} runs.
     *
     * @param tenantId   the tenant.
     * @param assemblyId the assembly.
     * @param sessionId  the draft session.
     * @param request    which record to verify, and the policy the request would compile with now.
     * @return the result, or why there is none.
     */
    public synchronized StudioVerificationResult verifyCompile(String tenantId, String assemblyId, String sessionId,
                                                               StudioVerifyRequest request) {
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        StudioVerifyRequest safe = request == null ? new StudioVerifyRequest(null, null, null) : request;
        Optional<StudioCompileRecord> current = freshness.currentRecord(session);
        if (current.isEmpty()) {
            StudioCompileFreshness.Verdict verdict = freshness.of(session, null, safe.requestPolicy());
            return verifyResponse(session, unverified(StudioAbsentReason.COMPILE_REQUIRED, verdict.message(), null, false));
        }
        Optional<StudioCompileRecord> chosen;
        if (safe.revision() != null) {
            chosen = latestRecordAt(session, safe.revision());
            if (chosen.isEmpty()) {
                return verifyResponse(session, new StudioVerificationResult(false, noRecordAt(session, safe.revision()),
                        null, true, null, null, null, List.of()));
            }
        } else if (safe.contractHash() != null) {
            chosen = session.compileRecords().reversed().stream()
                    .filter(record -> record.contractHash().equals(safe.contractHash()))
                    .findFirst();
            if (chosen.isEmpty()) {
                return verifyResponse(session, unverified(StudioAbsentReason.COMPILE_RECORD_UNAVAILABLE,
                        "no compile of session " + session.sessionId() + " produced contract " + safe.contractHash(),
                        null, true));
            }
        } else {
            chosen = current;
        }
        StudioCompileRecord record = chosen.get();
        StudioCompileProvenance provenance = StudioCompileProvenance.of(record);
        boolean historical = !record.equals(current.get());
        if (!historical) {
            StudioCompileFreshness.Verdict verdict = freshness.of(session, record, safe.requestPolicy());
            if (!verdict.current()) {
                return verifyResponse(session, unverified(verdict.state().name(), verdict.message(), provenance, false));
            }
        }
        if (!record.signed()) {
            return verifyResponse(session, unverified(StudioAbsentReason.NOT_SIGNED, "the compile at revision "
                    + record.revision() + " was not signed; compile with signing to verify it", provenance, historical));
        }
        TrustKeys trust = trustKeys();
        if (trust.problem() != null) {
            return verifyResponse(session, unverified(trust.problem().code(), trust.problem().message(), provenance, historical));
        }
        VerificationKeySet keys = trust.keys();
        SignedFabricContract signed;
        try {
            // The signed envelope is proved to be for the record's contract before it is verified, so the result
            // attests to the hash its provenance names and to nothing else.
            signed = compiledReader.load(session.tenantId(), record, true).signed();
        } catch (StudioCompiledContractReader.Unavailable | StudioCompiledContractReader.Inconsistent ex) {
            StudioAbsentReason problem = recordProblem(ex);
            return verifyResponse(session, unverified(problem.code(), problem.message(), provenance, historical));
        }
        SignatureVerificationResult signature = new SignatureVerifier().verify(signed, keys);
        CatalogDriftReport drift = new CatalogDriftChecker().check(signed.contract(), admittedCatalog(session.tenantId()));
        FabricVerificationReport report = FabricVerificationReport.of(signature, drift);
        return verifyResponse(session, new StudioVerificationResult(true, null, provenance, historical,
                signatureOf(report.signature()), report.catalogDrift().orElseThrow(), report.overallOk(), List.of()));
    }

    /**
     * The configured public trust keys, or why there are none: {@code TRUST_KEYS_UNAVAILABLE} when no folder is
     * configured ({@code unfurl.studio.trust.keysDir} or {@code UNFURL_STUDIO_TRUST_KEYS_DIR}) or it cannot be read.
     * {@code verify} and the emit-plan preview read the same keys.
     *
     * @param dir     the configured folder, when there is one.
     * @param keys    the loaded keys, when they could be read.
     * @param problem why not, when they could not.
     */
    private record TrustKeys(Path dir, VerificationKeySet keys, StudioAbsentReason problem) {
    }

    /** Loads the configured trust keys ({@link TrustKeys}). */
    private TrustKeys trustKeys() {
        String keysDir = configValue("unfurl.studio.trust.keysDir", "UNFURL_STUDIO_TRUST_KEYS_DIR");
        if (keysDir.isBlank()) {
            return new TrustKeys(null, null, new StudioAbsentReason(StudioAbsentReason.TRUST_KEYS_UNAVAILABLE,
                    "no trust-key directory is configured (unfurl.studio.trust.keysDir or UNFURL_STUDIO_TRUST_KEYS_DIR)"));
        }
        try {
            return new TrustKeys(Path.of(keysDir), new TrustKeyDirectoryLoader().load(Path.of(keysDir)), null);
        } catch (RuntimeException ex) {
            return new TrustKeys(Path.of(keysDir), null, new StudioAbsentReason(StudioAbsentReason.TRUST_KEYS_UNAVAILABLE,
                    "the trust-key directory " + keysDir + " cannot be read: " + ex.getMessage()));
        }
    }

    /**
     * Dependency injection seam: the emit-plan preview uses exactly these emitters instead of discovering the installed
     * ones, so a test can pin the real backend for a target kind when its classpath also registers stand-ins.
     *
     * @param bootstrap the emitter discovery every later preview uses.
     */
    void useEmitters(com.unfurl.deploy.core.EmitterBootstrap bootstrap) {
        this.emitPlanRunner = new StudioEmitPlanRunner(com.unfurl.fabric.runtime.RuntimeBindingSetGenerator::new, () -> bootstrap);
    }

    /** A cached preview and the key it was answered for: the record's contract hash and the environment's revision. */
    private record CachedEmitPlan(String key, StudioEmitPlanPreview response) {
    }

    /**
     * What {@link #emitPlan} gathered under the service lock: the response's fixed fields, and either the refusal found
     * before any emit or the runner's inputs and the cache key.
     */
    private record PreparedEmitPlan(StudioDraftSession session, String environment, Long environmentRevision,
                                    String environmentSha256, StudioCompileProvenance provenance, StudioAbsentReason refusal,
                                    StudioEmitPlanRunner.Inputs inputs, String cacheKey) {
    }

    /**
     * Read model: what a deploy of the session's current compile to one of the tenant's deployment environments emits
     * (LLD-fabric-studio-api, <i>StudioEmitPlanPreview</i>), produced by the deploy emitter's real pipeline.
     *
     * <p>Pattern: <b>Facade</b>. Inputs are gathered and checked under the service lock ({@link #prepareEmitPlan}); the
     * emit runs outside it, under a per-session lock, so a preview never blocks other Studio requests and at most one runs
     * per session. The answer is cached per session by the record's contract hash and the environment's name and content
     * hash (the record is current, so it was compiled with the request's policy at the session's revision); asking again
     * returns it without rendering, and a new record or any change to the environment's settings changes the key. The
     * revision is not part of the key: an environment deleted and created again reuses revisions.
     *
     * @param tenantId      the tenant.
     * @param assemblyId    the assembly.
     * @param sessionId     the draft session.
     * @param environment   the deployment environment's name (required).
     * @param requestPolicy the policy the request would compile with now, if it names one.
     * @return the preview, or why it is not available.
     * @throws IllegalArgumentException {@code ENVIRONMENT_REQUIRED} when no environment is named.
     */
    public StudioEmitPlanPreview emitPlan(String tenantId, String assemblyId, String sessionId, String environment,
                                          Optional<StudioDeploymentPolicyDraft> requestPolicy) {
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("ENVIRONMENT_REQUIRED: an emit-plan preview names a deployment environment");
        }
        PreparedEmitPlan prepared;
        synchronized (this) {
            prepared = prepareEmitPlan(tenantId, assemblyId, sessionId, environment.trim(), requestPolicy);
        }
        if (prepared.refusal() != null) {
            return emitPlanResponse(prepared, StudioEmitPlanRunner.Outcome.refused(prepared.refusal().code(),
                    prepared.refusal().message()));
        }
        String sessionKey = prepared.session().tenantId() + "\u0000" + prepared.session().sessionId();
        Object lock = emitPlanLocks.computeIfAbsent(sessionKey, ignored -> new Object());
        synchronized (lock) {
            CachedEmitPlan cached = emitPlanCache.get(sessionKey);
            if (cached != null && cached.key().equals(prepared.cacheKey())) {
                return cached.response();
            }
            StudioEmitPlanPreview response = emitPlanResponse(prepared, emitPlanRunner.run(prepared.inputs()));
            // Only an available answer is kept: a refusal can come from an input outside Studio state (a missing
            // operator folder, an untrusted key), and answering again shows its repair at once (LLD, Cache).
            if (response.available()) {
                emitPlanCache.put(sessionKey, new CachedEmitPlan(prepared.cacheKey(), response));
            } else {
                emitPlanCache.remove(sessionKey);
            }
            return response;
        }
    }

    /**
     * Gathers and checks a preview's inputs, in the order a refusal is reported: the environment exists; the current
     * record is current and consistent (the P2a rules, through {@link #viewContext}); it was signed; the trust keys and
     * Studio's signing key are configured; its selections are in the admitted catalog; and every jar its hosted contracts
     * pin is in the tenant's artifact store.
     */
    private PreparedEmitPlan prepareEmitPlan(String tenantId, String assemblyId, String sessionId, String environmentName,
                                             Optional<StudioDeploymentPolicyDraft> requestPolicy) {
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        String tenant = session.tenantId();
        Optional<StudioDeploymentEnvironment> environment = environments.find(tenant, environmentName);
        StudioViewContext.CompiledView compiled = viewContext(session, requestPolicy, new ArrayList<>()).compile().get();
        Long environmentRevision = environment.map(StudioDeploymentEnvironment::revision).orElse(null);
        String environmentSha256 = environment.map(StudioDeploymentEnvironment::contentSha256).orElse(null);
        java.util.function.Function<StudioAbsentReason, PreparedEmitPlan> refuse = reason -> new PreparedEmitPlan(session,
                environmentName, environmentRevision, environmentSha256, compiled.provenance(), reason, null, null);
        if (environment.isEmpty()) {
            return refuse.apply(new StudioAbsentReason(StudioDeploymentEnvironments.UNKNOWN,
                    "the tenant has no deployment environment named " + environmentName));
        }
        // The preview generates these inputs; an environment stored before the rule may still name one, and the emitter
        // would read it instead, so nothing renders until it is removed (LLD, Deployment Environments, Reserved options).
        List<StudioDeploymentEnvironments.Problem> reserved =
                StudioDeploymentEnvironments.reservedOptionProblems(environment.get().target());
        if (!reserved.isEmpty()) {
            return refuse.apply(new StudioAbsentReason(StudioDeploymentEnvironments.INVALID, String.join("; ",
                    reserved.stream().map(problem -> problem.path() + " " + problem.message()).toList())));
        }
        if (compiled.contract() == null) {
            return refuse.apply(compiled.absentReason());
        }
        StudioCompileRecord record = freshness.currentRecord(session).orElseThrow();
        if (!record.signed()) {
            return refuse.apply(new StudioAbsentReason(StudioAbsentReason.NOT_SIGNED, "the compile at revision "
                    + record.revision() + " was not signed; an emit needs a signed contract, so compile with signing"));
        }
        TrustKeys trust = trustKeys();
        if (trust.problem() != null) {
            return refuse.apply(trust.problem());
        }
        Optional<FabricContractSigner> signer = studioSigner();
        if (signer.isEmpty()) {
            return refuse.apply(new StudioAbsentReason("SIGNING_KEY_REQUIRED",
                    "the DCP runtime bundle is signed with Studio's signing key, and none is configured"));
        }
        StudioCompiledContractReader.Loaded loaded;
        byte[] signedEnvelope;
        byte[] profileBytes;
        try {
            loaded = compiledReader.load(tenant, record, true);
            signedEnvelope = compiledStore.get(tenant, record.signedEnvelopeSha256()).orElseThrow();
            profileBytes = compiledStore.get(tenant, record.substrateProfileSha256()).orElseThrow();
        } catch (StudioCompiledContractReader.Unavailable | StudioCompiledContractReader.Inconsistent ex) {
            return refuse.apply(recordProblem(ex));
        }
        com.unfurl.fabric.catalog.SelectedCatalogEntries selected;
        try {
            selected = com.unfurl.fabric.catalog.SelectedCatalogEntries.resolve(loaded.compiled(), admittedCatalog(tenant));
        } catch (com.unfurl.fabric.catalog.SelectedCatalogEntries.Refusal refusal) {
            return refuse.apply(new StudioAbsentReason("CATALOG_ENTRY_MISSING", refusal.getMessage()));
        }
        Map<String, Path> hostedCatalogJars = new java.util.TreeMap<>();
        Map<String, Path> pinnedJars = new java.util.TreeMap<>();
        Optional<StudioAbsentReason> missingJar = hostedJars(tenant, loaded.compiled(), hostedCatalogJars, pinnedJars);
        if (missingJar.isPresent()) {
            return refuse.apply(missingJar.get());
        }
        StudioDeploymentEnvironment env = environment.get();
        return new PreparedEmitPlan(session, environmentName, environmentRevision, environmentSha256, compiled.provenance(), null,
                new StudioEmitPlanRunner.Inputs(tenant, env, loaded.compiled(), loaded.signed(), signedEnvelope,
                        loaded.profile(), profileBytes, selected, signer.get(), trust.dir(), trust.keys(),
                        hostedCatalogJars, pinnedJars, compiled.providerNodes()),
                // Content, not revision: a deleted and re-created environment reuses revisions; and the files the run
                // reads from outside Studio state, so an edited or repaired one renders again (LLD, Cache).
                record.contractHash() + "|" + env.name() + "|" + env.contentSha256() + "|" + externalInputs(trust, env));
    }

    /**
     * The fingerprint of the files a preview reads from outside Studio state: the trust-key folder, Studio's signing key
     * files, and each operator path the environment's target options name ({@link StudioPathFingerprint}).
     */
    private String externalInputs(TrustKeys trust, StudioDeploymentEnvironment environment) {
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("trustKeys", trust.dir());
        paths.put("signingPrivateKey", configPath("unfurl.studio.signing.privateKey", "UNFURL_STUDIO_SIGNING_PRIVATE_KEY"));
        paths.put("signingPublicKey", configPath("unfurl.studio.signing.publicKey", "UNFURL_STUDIO_SIGNING_PUBLIC_KEY"));
        environment.target().options().forEach((key, value) -> {
            if (com.unfurl.fabric.deploy.EmitTargetInputs.isOperatorPathOption(key)) {
                paths.put("option:" + key, value == null || value.isBlank() ? null : Path.of(value));
            }
        });
        return StudioPathFingerprint.of(paths);
    }

    /** A configured path, or null when the setting is blank. */
    private Path configPath(String property, String environmentVariable) {
        String value = configValue(property, environmentVariable);
        return value.isBlank() ? null : Path.of(value);
    }

    /**
     * Finds the jars the contract's hosted in-process contracts pin (HLD-in-process-component-hosting §8), as the emitter
     * reads them ({@code ProviderArtifactPin.fromExtensions(...).all()}), in the tenant's artifact store, under the file
     * names they were admitted with. Each hosted component's own jar also goes in {@code hostedCatalogJars}, where its
     * packaged configuration schema is read.
     *
     * @return {@code COMPONENT_ARTIFACT_UNAVAILABLE} naming the first pinned jar that is not stored; empty when all are.
     */
    private Optional<StudioAbsentReason> hostedJars(String tenant, CompiledContract compiled,
                                                    Map<String, Path> hostedCatalogJars, Map<String, Path> pinnedJars) {
        if (compiled.bindingPlan() == null) {
            return Optional.empty();
        }
        Map<String, String> fileNames = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry visual : entriesByTenant.getOrDefault(tenant, List.of())) {
            if (visual.catalogEntryId().startsWith("uploaded:")) {
                fileNames.putIfAbsent(bareSha(visual.artifactSha256()), visual.catalogEntryId().substring("uploaded:".length()));
            }
        }
        companionsByTenant.getOrDefault(tenant, Map.of()).values()
                .forEach(companion -> fileNames.putIfAbsent(companion.sha256(), companion.fileName()));
        for (CompositionContract child : compiled.childContracts()) {
            Map<String, Object> extensions = child.metadata() == null ? Map.of() : child.metadata().extensions();
            boolean hosted = compiled.bindingPlan().entries().stream().anyMatch(entry -> entry.host().isPresent()
                    && entry.artifactCoordinates().equals(extensions.get("artifactCoordinates"))
                    && entry.capability().equals(child.binding().providerCapability()));
            if (!hosted) continue;
            Optional<com.unfurl.dcp.host.ProviderArtifactPin> pin = com.unfurl.dcp.host.ProviderArtifactPin.fromExtensions(extensions);
            if (pin.isEmpty()) continue; // the emitter refuses a hosted contract without a pin, naming it
            for (com.unfurl.dcp.host.ArtifactPin artifact : pin.get().all()) {
                Path stored = companionStore.path(tenant, artifact.sha256());
                String fileName = fileNames.get(artifact.sha256());
                if (!Files.isRegularFile(stored) || fileName == null) {
                    return Optional.of(new StudioAbsentReason("COMPONENT_ARTIFACT_UNAVAILABLE", artifact.coordinates()
                            + " (sha256 " + artifact.sha256() + ") is pinned by hosted contract " + child.contractId()
                            + " but its jar is not in the tenant's artifact store; admit "
                            + (fileName == null ? "it" : fileName) + " again"));
                }
                pinnedJars.put(fileName, stored);
                if (artifact.equals(pin.get().claimArtifact())) {
                    hostedCatalogJars.put(fileName, stored);
                }
            }
        }
        return Optional.empty();
    }

    /** A SHA-256 as lower-case hex without its {@code sha256:} prefix. */
    private static String bareSha(String sha) {
        return sha != null && sha.startsWith("sha256:") ? sha.substring("sha256:".length()) : sha;
    }

    /** The preview response for prepared inputs and the run's (or the refusal's) outcome, with its diagnostic snapshot. */
    private StudioEmitPlanPreview emitPlanResponse(PreparedEmitPlan prepared, StudioEmitPlanRunner.Outcome outcome) {
        StudioDraftSession session = prepared.session();
        StudioEmitPlanPreview response = new StudioEmitPlanPreview(session.tenantId(), session.assemblyId(),
                session.sessionId(), session.sceneRevision(), prepared.environment(), prepared.environmentRevision(),
                prepared.environmentSha256(),
                prepared.provenance(), outcome.absentReason() == null, outcome.absentReason(), outcome.targetKind(),
                outcome.artifacts(), outcome.requiredSecrets(), outcome.runtimeBindings(), outcome.bindingsSha256(),
                outcome.emitManifestSha256(), List.of());
        return response.withDiagnosticArtifacts(List.of(diagnosticArtifact(session.tenantId(), "emit-plan",
                "emit-plan.json", response)));
    }

    /**
     * Query: the tenant's deployment environments, by name (LLD-fabric-studio-api, <i>Deployment Environments</i>).
     */
    public List<StudioDeploymentEnvironment> deploymentEnvironments(String tenantId) {
        return environments.list(normalizeTenant(tenantId));
    }

    /**
     * Query: one deployment environment.
     *
     * @throws StudioDeploymentEnvironments.Unknown when the tenant has none with that name.
     */
    public StudioDeploymentEnvironment deploymentEnvironment(String tenantId, String name) {
        return environments.find(normalizeTenant(tenantId), name)
                .orElseThrow(() -> new StudioDeploymentEnvironments.Unknown(name));
    }

    /**
     * Command: creates or replaces a deployment environment, revision-checked, after the save-time check.
     *
     * @throws StudioDeploymentEnvironments.Conflict when the expected revision is not the stored one.
     * @throws StudioDeploymentEnvironments.Invalid  listing every problem of the content.
     */
    public StudioDeploymentEnvironment saveDeploymentEnvironment(String tenantId, String name,
                                                                 StudioDeploymentEnvironmentRequest request) {
        return environments.save(normalizeTenant(tenantId), name, request);
    }

    /**
     * Command: deletes a deployment environment, revision-checked.
     *
     * @throws StudioDeploymentEnvironments.Unknown  when there is none with that name.
     * @throws StudioDeploymentEnvironments.Conflict when the expected revision is not the stored one.
     */
    public void deleteDeploymentEnvironment(String tenantId, String name, long expectedRevision) {
        environments.delete(normalizeTenant(tenantId), name, expectedRevision);
    }

    /**
     * Read model: one Studio workbench view's typed data (LLD-fabric-studio-api, <i>StudioViewProjection</i>). The view
     * projects the draft's {@code /dynamic-dcp} projection for {@code sessionId}, grounds each component node's claim the
     * way compile does, and, for compile-derived sections, reads the current compile record under the P2a freshness and
     * consistency rules. Never changes the session and is never a validity source.
     *
     * <p>Pattern: <b>Facade</b> over the {@code /dynamic-dcp} projection, claim grounding, {@link StudioCompileFreshness},
     * {@link StudioCompiledContractReader} and {@link #verifyCompile}; the per-view reading is the
     * {@link StudioViewProjector} Strategy.
     *
     * @param tenantId      the tenant.
     * @param assemblyId    the assembly.
     * @param sessionId     the draft session (required).
     * @param view          {@code flow}, {@code data}, {@code topology} or {@code runtime}.
     * @param requestPolicy the policy the request would compile with now, if it names one.
     * @return the view's sections, diagnostics and diagnostic snapshot.
     * @throws IllegalArgumentException {@code VIEW_UNKNOWN} for another view, or when {@code sessionId} is blank.
     */
    public synchronized StudioViewProjection viewProjection(String tenantId, String assemblyId, String sessionId,
                                                            String view,
                                                            Optional<StudioDeploymentPolicyDraft> requestPolicy) {
        StudioViewProjector projector = StudioViewProjector.forView(view);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("VIEW_SESSION_REQUIRED: a view describes a draft; sessionId is required");
        }
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        String tenant = session.tenantId();
        List<StudioDcpDiagnostic> diagnostics = new ArrayList<>();
        StudioViewContext context = viewContext(session, requestPolicy, diagnostics);
        StudioViewProjector.Sections sections = projector.project(context);
        StudioViewProjection response = new StudioViewProjection(projector.id(), tenant, session.assemblyId(), sessionId,
                session.sceneRevision(), sections.externalOwners, sections.externalProtocolOffers, sections.ownedState,
                sections.ownedDecisions, sections.operations, sections.dataMappings, sections.transports,
                sections.monitoring, sections.telemetry, sections.verification, diagnostics, List.of());
        return response.withDiagnosticArtifacts(List.of(
                diagnosticArtifact(tenant, "view-" + projector.id(), "view-" + projector.id() + ".json", response)));
    }

    /**
     * Read model: one perspective overlay's annotations on the draft's nodes, and its findings (LLD-fabric-studio-api,
     * <i>StudioOverlayAnnotations</i>). It reads the same context as the views ({@link #viewContext}) and never gates:
     * nothing here changes a session, a revision or a contract.
     *
     * <p>Pattern: <b>Facade</b> over the shared view context; the per-overlay reading is the
     * {@link StudioOverlayProjector} Strategy.
     *
     * @param tenantId      the tenant.
     * @param assemblyId    the assembly.
     * @param sessionId     the draft session (required).
     * @param overlay       {@code security}, {@code performance} or {@code compliance}.
     * @param view          the view the overlay annotates ({@code flow}, {@code data}, {@code topology}, {@code runtime}).
     * @param requestPolicy the policy the request would compile with now, if it names one.
     * @return the overlay's annotations, findings, left-out sources and diagnostics.
     * @throws IllegalArgumentException {@code OVERLAY_UNKNOWN}, {@code OVERLAY_NOT_BUILT}, {@code VIEW_UNKNOWN}, or
     *                                  {@code VIEW_SESSION_REQUIRED}.
     */
    public synchronized StudioOverlayAnnotations overlayAnnotations(String tenantId, String assemblyId, String sessionId,
                                                                    String overlay, String view,
                                                                    Optional<StudioDeploymentPolicyDraft> requestPolicy) {
        StudioOverlayProjector projector = StudioOverlayProjector.forOverlay(overlay);
        String viewId = StudioViewProjector.forView(view).id();
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("VIEW_SESSION_REQUIRED: an overlay annotates a draft; sessionId is required");
        }
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        List<StudioDcpDiagnostic> diagnostics = new ArrayList<>();
        StudioOverlayProjector.Result result = projector.project(viewContext(session, requestPolicy, diagnostics), session);
        boolean available = !result.annotations().isEmpty() || !result.findings().isEmpty();
        StudioOverlayAnnotations response = new StudioOverlayAnnotations(projector.id(), viewId, session.tenantId(),
                session.assemblyId(), sessionId, session.sceneRevision(), result.provenance(), available,
                available ? null : result.emptyReason(), result.annotations(), result.findings(), result.staleSources(),
                diagnostics, List.of());
        return response.withDiagnosticArtifacts(List.of(diagnosticArtifact(session.tenantId(), "overlay-" + projector.id(),
                "overlay-" + projector.id() + ".json", response)));
    }

    /**
     * The context views and overlays read (LLD-fabric-studio-api, <i>StudioViewProjection</i>, Grounding): each component
     * node of the draft's {@code /dynamic-dcp} projection grounded to the claim compile uses, the current compile under
     * the P2a rules, and its verification, both evaluated lazily. A node that does not ground is added to
     * {@code diagnostics} as {@code CATALOG_ENTRY_NOT_ADMITTED}, and nothing is projected for it.
     *
     * <p>Pattern: <b>Factory</b> for {@link StudioViewContext}, shared by {@link #viewProjection} and
     * {@link #overlayAnnotations} so both read the same grounding.
     */
    private StudioViewContext viewContext(StudioDraftSession session, Optional<StudioDeploymentPolicyDraft> requestPolicy,
                                          List<StudioDcpDiagnostic> diagnostics) {
        String tenant = session.tenantId();
        List<StudioViewContext.GroundedNode> nodes = new ArrayList<>();
        StudioDynamicDcpProjection projection = dynamicDcpProjection(tenant, session.assemblyId(), session.sessionId());
        // Where each node sits in the CONTAINS tree, from the projection's own edges, so an annotated parent edge is one
        // the view draws (P2e, Resilience & Faults).
        Map<String, String> labels = new LinkedHashMap<>();
        projection.nodes().forEach(node -> labels.put(node.nodeId(), node.label()));
        Map<String, String> containedBy = new LinkedHashMap<>();
        projection.edges().stream().filter(edge -> "CONTAINS".equals(edge.relationship()))
                .forEach(edge -> containedBy.putIfAbsent(edge.toNodeId(), edge.fromNodeId()));
        for (StudioDynamicDcpNode node : projection.nodes()) {
            if (node.catalogEntryId() == null || node.catalogEntryId().isBlank()) {
                continue;
            }
            String parent = containedBy.get(node.nodeId());
            groundedClaim(tenant, node.catalogEntryId()).ifPresentOrElse(
                    claim -> nodes.add(new StudioViewContext.GroundedNode(node.nodeId(), claim, node.label(), parent,
                            parent == null ? null : labels.getOrDefault(parent, parent))),
                    () -> diagnostics.add(new StudioDcpDiagnostic("ERROR", "CATALOG_ENTRY_NOT_ADMITTED",
                            "nodes[" + node.nodeId() + "]", "catalog entry " + node.catalogEntryId()
                            + " has no admitted claim; nothing is projected for it")));
        }
        // A draft component whose entry the catalog no longer has is not in the projection; the view names it so the
        // operator can remove it (LLD-fabric-studio-api, Tenant Catalog and Assets).
        for (String missing : draftEntries(session).missing()) {
            diagnostics.add(new StudioDcpDiagnostic("ERROR", "CATALOG_ENTRY_MISSING", "draft[" + missing + "]",
                    missingEntryWarning(tenant, missing)));
        }
        Map<java.net.URI, String> providerNodes = new LinkedHashMap<>();
        nodes.forEach(node -> {
            if (node.claim().identity() != null && node.claim().identity().uri() != null) {
                providerNodes.putIfAbsent(node.claim().identity().uri(), node.nodeId());
            }
        });
        return new StudioViewContext(nodes,
                () -> currentCompiledView(session, requestPolicy, providerNodes),
                () -> verifyCompile(tenant, session.assemblyId(), session.sessionId(),
                        new StudioVerifyRequest(null, null, requestPolicy.orElse(null))),
                () -> needForSession(session, "", "").requiredCapabilities().stream()
                        .anyMatch(requirement -> StudioOverlayProjector.IDENTITY_CAPABILITY.equals(requirement.capability())));
    }

    /**
     * The claim a catalog entry grounds to, exactly as compile reads it ({@code catalogEntryForVisual}), or empty when the
     * entry is unknown, display-only or no longer matches its pinned hash. Nothing is approximated from visual metadata.
     */
    private Optional<com.unfurl.dcp.claim.Claim> groundedClaim(String tenant, String catalogEntryId) {
        Optional<StudioVisualCatalogEntry> visual = entriesByTenant.getOrDefault(tenant, List.of()).stream()
                .filter(entry -> catalogEntryId.equals(entry.catalogEntryId()))
                .findFirst();
        if (visual.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(catalogEntryForVisual(tenant, visual.get()).claimDescriptor().claim());
        } catch (CatalogEntryNotAdmittedException notAdmitted) {
            return Optional.empty();
        }
    }

    /**
     * The current compile record's contract for compile-derived view sections, under the P2a rules: COMPILE_REQUIRED
     * without a record, COMPILE_STALE when the draft or the request's policy moved on, and COMPILE_RECORD_UNAVAILABLE or
     * COMPILE_RECORD_INCONSISTENT when its bytes cannot be read or do not belong to it.
     */
    private StudioViewContext.CompiledView currentCompiledView(StudioDraftSession session,
                                                               Optional<StudioDeploymentPolicyDraft> requestPolicy,
                                                               Map<java.net.URI, String> providerNodes) {
        Optional<StudioCompileRecord> current = freshness.currentRecord(session);
        StudioCompileFreshness.Verdict verdict = freshness.of(session, current.orElse(null), requestPolicy);
        StudioCompileProvenance provenance = current.map(StudioCompileProvenance::of).orElse(null);
        if (!verdict.current()) {
            return new StudioViewContext.CompiledView(null, provenance,
                    new StudioAbsentReason(verdict.state().name(), verdict.message()), Map.of());
        }
        try {
            CompiledContract contract = compiledReader.load(session.tenantId(), current.get(), false).compiled();
            return new StudioViewContext.CompiledView(contract, provenance, null, Map.copyOf(providerNodes));
        } catch (StudioCompiledContractReader.Unavailable | StudioCompiledContractReader.Inconsistent ex) {
            return new StudioViewContext.CompiledView(null, provenance, recordProblem(ex), Map.of());
        }
    }

    /** An unavailable verification result. */
    private static StudioVerificationResult unverified(String code, String message, StudioCompileProvenance provenance,
                                                       boolean historical) {
        return new StudioVerificationResult(false, new StudioAbsentReason(code, message), provenance, historical,
                null, null, null, List.of());
    }

    /** The signature check as Studio shows it: whether it verified, the result variant, and its explanation. */
    private static StudioVerificationResult.Signature signatureOf(SignatureVerificationResult result) {
        return switch (result) {
            case SignatureVerificationResult.Ok ok -> new StudioVerificationResult.Signature(true, "Ok",
                    "signed by key " + ok.fingerprint() + " with " + ok.algorithm());
            case SignatureVerificationResult.KeyNotPinned miss ->
                    new StudioVerificationResult.Signature(false, "KeyNotPinned", miss.detail());
            case SignatureVerificationResult.SignatureInvalid bad ->
                    new StudioVerificationResult.Signature(false, "SignatureInvalid", bad.detail());
            case SignatureVerificationResult.HashMismatch mismatch ->
                    new StudioVerificationResult.Signature(false, "HashMismatch", mismatch.detail());
        };
    }

    /**
     * The tenant's current admitted catalog for the drift check: every catalog entry {@code catalogEntryForVisual}
     * grounds (a display-only entry is not admitted, so it is not part of it), with the tenant's stored companions.
     */
    private com.unfurl.fabric.catalog.Catalog admittedCatalog(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        List<CatalogEntry> entries = new ArrayList<>();
        for (StudioVisualCatalogEntry visual : entriesByTenant.getOrDefault(tenant, List.of())) {
            try {
                entries.add(catalogEntryForVisual(tenant, visual));
            } catch (CatalogEntryNotAdmittedException notAdmitted) {
                // Display-only or mismatched entries are not part of the admitted catalog.
            }
        }
        Map<String, com.unfurl.fabric.catalog.CompanionArtifact> companions = new java.util.TreeMap<>();
        companionsByTenant.getOrDefault(tenant, Map.of()).forEach((coordinates, record) -> {
            Path stored = companionStore.path(tenant, record.sha256());
            // A companion whose bytes are gone is not available, so the drift check reports it missing.
            if (Files.isRegularFile(stored)) {
                companions.put(coordinates, new com.unfurl.fabric.catalog.CompanionArtifact(coordinates,
                        record.sha256(), stored, List.of(stored)));
            }
        });
        return new com.unfurl.fabric.catalog.Catalog(entries, companions);
    }

    /** The absent reason for a record whose bytes cannot be read, or do not belong to it. */
    private static StudioAbsentReason recordProblem(RuntimeException ex) {
        return new StudioAbsentReason(ex instanceof StudioCompiledContractReader.Inconsistent
                ? StudioAbsentReason.COMPILE_RECORD_INCONSISTENT
                : StudioAbsentReason.COMPILE_RECORD_UNAVAILABLE, ex.getMessage());
    }

    /** The session's latest compile record at a revision. */
    private static Optional<StudioCompileRecord> latestRecordAt(StudioDraftSession session, long revision) {
        return session.compileRecords().reversed().stream().filter(record -> record.revision() == revision).findFirst();
    }

    /** The absent reason for a revision with no compile record. */
    private static StudioAbsentReason noRecordAt(StudioDraftSession session, long revision) {
        return new StudioAbsentReason(StudioAbsentReason.COMPILE_RECORD_UNAVAILABLE,
                "no compile is recorded at revision " + revision + " of session " + session.sessionId());
    }

    /** Attaches the hash-pinned diagnostic snapshot of a contract-diff response. */
    private StudioContractDiff diffResponse(StudioDraftSession session, StudioContractDiff response) {
        return response.withDiagnostics(List.of(
                diagnosticArtifact(session.tenantId(), "contract-diff", "contract-diff.json", response)));
    }

    /** Attaches the hash-pinned diagnostic snapshot of a verify response. */
    private StudioVerificationResult verifyResponse(StudioDraftSession session, StudioVerificationResult response) {
        return response.withDiagnostics(List.of(
                diagnosticArtifact(session.tenantId(), "contract-verify", "contract-verify.json", response)));
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
        // A refused compile records nothing; a NO_MATCH carries its typed causes. Session mode applies no trust
        // classification (LLD gap: a session's trust policy is not applied), so it has no trust rejections.
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
                receivedRevision,
                List.of(),
                List.of(),
                invalid.reasons(),
                List.of(),
                null);
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
                List.of(),
                List.of(diagnosticArtifact(session.tenantId(), "compile-invalid", "compile-response.json", response)),
                response.reasons(),
                response.trustRejections(),
                null);
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
        List<StudioVisualCatalogEntry> selectedVisuals;
        try {
            selectedVisuals = selectedVisualEntriesForSession(session);
        } catch (IllegalArgumentException ex) {
            return CandidateBuild.invalid(new InvalidDraft("CATALOG_ENTRY_NOT_FOUND", ex.getMessage()));
        }
        if (selectedVisuals.isEmpty()) {
            return CandidateBuild.invalid(new InvalidDraft("EMPTY_DRAFT", "draft contains no components"));
        }
        List<CatalogEntry> selected = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> catalogEntryIdsByCoordinates = new LinkedHashMap<>();
        for (StudioVisualCatalogEntry visual : selectedVisuals) {
            try {
                CatalogEntry entry = withTenantCompanions(session.tenantId(), catalogEntryForVisual(session.tenantId(), visual));
                selected.add(entry);
                catalogEntryIdsByCoordinates.putIfAbsent(entry.artifact().coordinates(), visual.catalogEntryId());
            } catch (CatalogEntryNotAdmittedException ex) {
                return CandidateBuild.invalid(new InvalidDraft("CATALOG_ENTRY_NOT_ADMITTED", ex.getMessage()));
            } catch (CompanionMissingException ex) {
                return CandidateBuild.invalid(new InvalidDraft(com.unfurl.fabric.catalog.CompanionCoordinates.MISSING, ex.getMessage()));
            }
            warnings.addAll(visual.warnings());
        }
        CandidateValidity validity = new CandidateValidator().validate(selected, need);
        if (!validity.isValid()) {
            // The details text and the typed reasons come from the same presenter over the same conflicts
            // (LLD-fabric-studio-api, Structured match failures and trust rejections).
            return CandidateBuild.invalid(new InvalidDraft("NO_MATCH",
                    NoMatchExplanation.draftDetails(validity.conflicts()),
                    NoMatchExplanation.draftCauses(validity.conflicts())));
        }
        CompositionCandidate candidate = new CompositionCandidate(
                CompositionCandidate.computeId(selected),
                selected.stream().sorted(CatalogEntry.CANONICAL_ORDER).toList(),
                satisfiedRequiredCapabilities(selected, need),
                satisfiedOptionalCapabilities(selected, need),
                dependencyBindings(selected),
                List.<PlanningWarning>of(),
                new Scorer().score(selected, need));
        return CandidateBuild.valid(candidate, warnings, catalogEntryIdsByCoordinates);
    }

    /**
     * Adapter: grounds the replayed draft inventory in the tenant catalog so
     * projection, deployment resolution, and compile share one source of truth.
     */
    private List<StudioVisualCatalogEntry> selectedVisualEntriesForSession(StudioDraftSession session) {
        DraftEntries draft = draftEntries(session);
        if (!draft.missing().isEmpty()) {
            throw new IllegalArgumentException(missingEntryWarning(session.tenantId(), draft.missing().get(0)));
        }
        return draft.present();
    }

    /**
     * Replays a draft's membership against the tenant catalog: the entries the catalog has, in draft order, and the ids
     * of those it no longer has (removed, or never a real entry). Resolution and compile refuse a draft with missing
     * entries; the projection draws the rest and names them.
     */
    private DraftEntries draftEntries(StudioDraftSession session) {
        List<StudioVisualCatalogEntry> catalog = entriesByTenant.computeIfAbsent(session.tenantId(), this::catalogEntries);
        Map<String, StudioVisualCatalogEntry> byId = new LinkedHashMap<>();
        catalog.forEach(entry -> byId.put(entry.catalogEntryId(), entry));
        List<StudioVisualCatalogEntry> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String entryId : selectedCatalogEntryIds(session)) {
            StudioVisualCatalogEntry visual = byId.get(entryId);
            if (visual == null) {
                if (!missing.contains(entryId)) missing.add(entryId);
            } else {
                present.add(visual);
            }
        }
        return new DraftEntries(List.copyOf(present), List.copyOf(missing));
    }

    /** A draft's components replayed against the catalog: those it has, and the ids of those it no longer has. */
    private record DraftEntries(List<StudioVisualCatalogEntry> present, List<String> missing) {
    }

    /**
     * The words for a draft component whose entry the catalog no longer has, and how to fix it. Grounding failures that
     * report it carry {@code CATALOG_ENTRY_NOT_FOUND}; the projection's warning carries {@code CATALOG_ENTRY_MISSING}.
     */
    private static String missingEntryWarning(String tenantId, String entryId) {
        return "CATALOG_ENTRY_MISSING: draft component '" + entryId + "' is no longer in tenant '" + tenantId
                + "''s catalog; remove it from the draft (REMOVE_COMPONENT) or admit its jar";
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
     * Repository lookup: returns the runtime catalog entry backing a draft component. The entry is the one admitted or
     * scanned in this process, or, after a restart or snapshot load, the one rebuilt by parsing the manifest text the
     * visual entry kept from the artifact, accepted only when its recomputed claim hash equals the pinned one. Studio
     * never builds a stand-in claim from visual metadata (DCP HLD-C §3.10).
     *
     * @param tenantId the tenant whose catalog the draft uses.
     * @param entry    the draft component's visual catalog entry.
     * @return the catalog entry carrying the component's own claim.
     * @throws CatalogEntryNotAdmittedException when the entry is display-only or its manifest does not match its hash.
     */
    private CatalogEntry catalogEntryForVisual(String tenantId, StudioVisualCatalogEntry entry) {
        String tenant = normalizeTenant(tenantId);
        CatalogEntry known = catalogEntriesByTenant
                .getOrDefault(tenant, Map.of())
                .get(entry.catalogEntryId());
        if (known != null) {
            return known;
        }
        if (!entry.admitted()) {
            throw new CatalogEntryNotAdmittedException(entry.catalogEntryId() + " is display-only: it has no claim read"
                    + " from a component artifact; admit the component's jar to compose it");
        }
        CatalogEntry rebuilt;
        try {
            rebuilt = catalogEntryFromManifest(entry.catalogManifest(), entry.artifactSha256(),
                    storedJar(tenant, entry.artifactSha256()));
        } catch (CatalogScanException ex) {
            throw new CatalogEntryNotAdmittedException(entry.catalogEntryId() + " has an unreadable catalog manifest: "
                    + ex.getMessage());
        }
        if (!artifactDescriptorSha(rebuilt.claimDescriptor().claimHash()).equals(artifactDescriptorSha(entry.claimHash()))) {
            throw new CatalogEntryNotAdmittedException(entry.catalogEntryId() + " has a catalog manifest whose claim no"
                    + " longer matches its pinned hash " + entry.claimHash() + "; admit the component's jar again");
        }
        catalogEntriesByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(entry.catalogEntryId(), rebuilt);
        return rebuilt;
    }

    /**
     * Companion resolution for compile (HLD-in-process-component-hosting §3): a hosted entry's
     * {@code hosted_activation.companions} are resolved against the tenant's admitted companions, each with its stored
     * path so the shared-API check reads the admitted bytes. An entry the catalog scan already resolved, or one that
     * names no companions, is returned unchanged.
     *
     * @throws CompanionMissingException {@code CATALOG_COMPANION_MISSING} naming every companion the tenant lacks
     */
    private CatalogEntry withTenantCompanions(String tenantId, CatalogEntry entry) {
        var activation = entry.metadata().optionalHostedActivation();
        if (activation.isEmpty() || activation.get().companions().isEmpty() || !entry.companions().isEmpty()) {
            return entry;
        }
        String tenant = normalizeTenant(tenantId);
        Map<String, StudioCompanionRecord> records = companionsByTenant.getOrDefault(tenant, Map.of());
        List<String> missing = activation.get().companions().stream().filter(c -> !records.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new CompanionMissingException(com.unfurl.fabric.catalog.CompanionCoordinates.MISSING + ": "
                    + entry.artifact().coordinates() + " needs companions this tenant has not admitted: "
                    + String.join(", ", missing) + "; upload their jars");
        }
        List<com.unfurl.fabric.catalog.CompanionArtifact> companions = new ArrayList<>();
        for (String coordinates : activation.get().companions()) {
            StudioCompanionRecord record = records.get(coordinates);
            Path stored = companionStore.path(tenant, record.sha256());
            if (!Files.isRegularFile(stored)) {
                throw new CompanionMissingException(com.unfurl.fabric.catalog.CompanionCoordinates.MISSING + ": the stored bytes of "
                        + coordinates + " are no longer in the companion store; upload its jar again");
            }
            companions.add(new com.unfurl.fabric.catalog.CompanionArtifact(coordinates, record.sha256(), stored, List.of(stored)));
        }
        return entry.withCompanions(companions);
    }

    /** Raised when a hosted entry names companions the tenant has not admitted. */
    private static final class CompanionMissingException extends RuntimeException {
        /** @param message starts with {@code CATALOG_COMPANION_MISSING} and names the coordinates. */
        private CompanionMissingException(String message) {
            super(message);
        }
    }

    /**
     * Raised when a draft component has no claim read from its artifact, so compile cannot ground it.
     */
    private static final class CatalogEntryNotAdmittedException extends RuntimeException {
        /**
         * @param message names the entry and how to admit it.
         */
        private CatalogEntryNotAdmittedException(String message) {
            super(message);
        }
    }

    /**
     * Projector: restores a component shape profile from Studio's persisted dynamic
     * metadata before falling back to the visual badge category. This keeps uploaded
     * manifest entries deployment-resolvable after a server restart or catalog snapshot
     * load, where the original runtime catalog entry map is not persisted.
     */
    private ComponentShapeProfile shapeProfileFromVisual(StudioVisualCatalogEntry entry) {
        ComponentShapeProfile dynamicProfile = componentShapeProfileFromDynamic(entry.dynamicComposition());
        if (dynamicProfile != null) {
            return dynamicProfile;
        }
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
     * Adapter: decodes the deployment shape profile stored in
     * {@code dynamicComposition.componentShapeProfile}. The map is deliberately
     * string/list based so Studio JSON snapshots remain stable and UI clients can
     * ignore it without depending on deployment-domain classes.
     */
    private ComponentShapeProfile componentShapeProfileFromDynamic(Map<String, Object> dynamicComposition) {
        Object profile = dynamicComposition == null ? null : dynamicComposition.get("componentShapeProfile");
        if (!(profile instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            DeploymentShape defaultShape = DeploymentShape.valueOf(stringValue(map.get("defaultShape"), ""));
            EnumSet<DeploymentShape> supported = EnumSet.noneOf(DeploymentShape.class);
            Object supportedRaw = map.get("supportedShapes");
            if (supportedRaw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String text && !text.isBlank()) {
                        supported.add(DeploymentShape.valueOf(text));
                    }
                }
            }
            if (supported.isEmpty()) {
                supported.add(defaultShape);
            }
            return new ComponentShapeProfile(defaultShape, supported, Map.of());
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
     * Projector: records component-to-component and external dependency bindings for the
     * selected entry set so the compiled audit has a truthful draft dependency view.
     * Every need is recorded, bound or not. An optional dependency is recorded only when it is actually bound, by a
     * selected provider or externally; an unprovided one is no binding (HLD-unfurl-fabric, Core Flow step 3), and
     * {@link com.unfurl.fabric.matcher.OptionalDependencies} reports it instead.
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
                        && (dep.contains("substrate=true") || CandidateValidator.isExternallyOwnedDependency(dep));
                bindings.add(new DependencyBinding(dep, provider, hostBound));
            }
            for (String dep : entry.claimDescriptor().claim().dependencies().optional()) {
                String capability = capabilityNameFromDependencyUri(dep);
                String provider = capability == null ? null : providersByCapability.get(capability);
                boolean hostBound = provider == null && CandidateValidator.isExternallyOwnedDependency(dep);
                if (provider != null || hostBound) {
                    bindings.add(new DependencyBinding(dep, provider, hostBound));
                }
            }
        }
        return bindings;
    }

    /**
     * Dependency audit of hosting (HLD-in-process-component-hosting §10): one row per hosted binding-plan entry with the
     * host edge (the placement and the child contract's consumer) and each pinned companion with its hash, read from
     * the compiled child contract. Empty when the candidate hosts nothing.
     */
    private static List<Map<String, Object>> hostedComponentsAudit(CompiledContract compiled) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (compiled.bindingPlan() == null) {
            return rows;
        }
        for (var entry : compiled.bindingPlan().entries()) {
            if (entry.host().isEmpty()) continue;
            for (var child : compiled.childContracts()) {
                if (!child.binding().providerCapability().equals(entry.capability()) || child.metadata() == null
                        || !entry.artifactCoordinates().equals(child.metadata().extensions().get("artifactCoordinates"))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("component", entry.componentId());
                row.put("capability", entry.capability());
                row.put("host", com.unfurl.fabric.compile.HostedComponents.describe(entry.host().get()));
                row.put("consumer", Map.of("claimUri", child.parties().consumer().claimUri().toString(),
                        "claimVersion", child.parties().consumer().claimVersion()));
                row.put("companions", com.unfurl.fabric.compile.HostedComponents.companionPins(child).stream()
                        .map(pin -> Map.of("coordinates", pin.coordinates(), "sha256", pin.sha256())).toList());
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Compiler adapter: mirrors the CLI compile/profile pipeline using an already resolved
     * deployment binding plan. Like the CLI, it records the selection mode in the decision audit: a Studio draft is
     * composed by the operator's own intents, so the mode is {@code MANUAL} (LLD-fabric-studio-api, Sessions).
     */
    private CompiledArtifacts compileArtifacts(
            CompositionCandidate candidate,
            Need need,
            ResolverOutcome resolved
    ) {
        BindingPlan bindingPlan = resolved.plan();
        // Same hosting rules as the CLI: hosted components name their placed host as consumer and pin their companions.
        CompiledContract compiled = new ContractCompiler()
                .compile(candidate, need, new HostOwnerMeta(null, null, null),
                        com.unfurl.fabric.compile.HostedComponents.from(candidate.entries(), bindingPlan));
        SubstrateProfile profile = new SubstrateProfileDeriver().derive(candidate, bindingPlan);
        CompositionContractCodec rootCodec = new CompositionContractCodec();
        CompiledContractCodec compiledCodec = new CompiledContractCodec();
        SubstrateProfileCodec profileCodec = new SubstrateProfileCodec();
        SubstrateProfile hashedProfile = profile.withProfileHash(profileCodec.computeProfileHash(profile));
        CompiledContract withProfileHash = new CompiledContract(
                compiled.contract(),
                compiled.childContracts(),
                selectionsWithDeploymentShapes(compiled.selections(), bindingPlan),
                compiled.audit().withSelectionMode(STUDIO_SELECTION_MODE),
                hashedProfile.profileHash(),
                bindingPlan,
                compiled.signature());
        return new CompiledArtifacts(
                withProfileHash,
                rootCodec.write(withProfileHash.contract()),
                compiledCodec.write(withProfileHash),
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
        return signCompiledContract(contract, studioSigner());
    }

    /**
     * Signing adapter: freezes and signs the light root DCP contract as the primary
     * deployment handoff artifact.
     */
    private SignedArtifact signRootContract(CompositionContract contract, Optional<FabricContractSigner> signer) {
        if (signer.isEmpty()) {
            return SignedArtifact.invalid(new InvalidDraft(
                    "SIGNING_KEY_REQUIRED",
                    "signing requested but Studio signing keys are not configured"));
        }
        try {
            ContractFreezer freezer = new ContractFreezer(new SigningKeyRef(signer.get().keyFingerprint()));
            return SignedArtifact.valid(freezer.freeze(contract, signer.get()).canonicalBytes());
        } catch (RuntimeException ex) {
            return SignedArtifact.invalid(new InvalidDraft("SIGNING_FAILED", ex.getMessage()));
        }
    }

    /**
     * Signing adapter: signs with an already loaded Studio signer so companion artifacts can reuse
     * the same key without re-reading environment configuration.
     */
    private SignedArtifact signCompiledContract(CompiledContract contract, Optional<FabricContractSigner> signer) {
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
     * Bundle builder: delegates to {@link com.unfurl.fabric.compile.DcpRuntimeBundleWriter}, the one writer Studio and
     * {@code fabric sign --dcp-runtime-bundle} share (HLD-in-process-component-hosting §7).
     */
    private byte[] dcpRuntimeBundle(
            CompiledContract compiled,
            CompositionCandidate candidate,
            FabricContractSigner signer
    ) {
        return new com.unfurl.fabric.compile.DcpRuntimeBundleWriter().write(compiled,
                candidate == null ? List.of() : candidate.entries(), signer, signer.keyFingerprint());
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
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
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
     * Factory: stores immutable non-JSON diagnostic bytes and returns the hash-pinned
     * diagnostic endpoint metadata used by Studio support downloads.
     */
    private StudioExportArtifact diagnosticFileArtifact(
            String tenantId,
            String kind,
            String fileName,
            String mediaType,
            byte[] bytes
    ) {
        String tenant = normalizeTenant(tenantId);
        String artifactId = kind + "-" + UUID.randomUUID();
        byte[] safeBytes = bytes == null ? new byte[0] : bytes.clone();
        String sha = sha256(safeBytes);
        String safeMediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        diagnosticArtifactsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(artifactId, new StudioAssetContent(safeBytes, safeMediaType, sha));
        return new StudioExportArtifact(
                artifactId,
                safeMediaType,
                sha,
                "/studio/tenants/" + tenant + "/diagnostic-artifacts/" + artifactId
                        + "/content?sha256=" + sha + "&fileName=" + fileName);
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
                Map.copyOf(sessionsByTenant),
                Map.copyOf(filesByTenant),
                Map.copyOf(sessionFileLinksByTenant),
                Map.copyOf(catalogSnapshotsByTenant),
                Map.copyOf(companionsByTenant),
                Map.copyOf(environmentsByTenant)));
    }

    /**
     * Catalog bootstrapper: loads a tenant's initial visual catalog and records any
     * real scanned manifest entries for compile/deployment reuse.
     *
     * <p>Fall-through order: real `META-INF/unfurl-catalog.yaml` JAR entries,
     * package visual assets, then hardcoded fixtures.
     */
    /**
     * Load-time rule (LLD-fabric-studio-api, What a catalog entry is): state saved before every entry had to carry a
     * claim may hold entries without a manifest. They are dropped, never served, and the log names each one.
     */
    private static List<StudioVisualCatalogEntry> withClaimsOnly(String where, List<StudioVisualCatalogEntry> entries) {
        List<StudioVisualCatalogEntry> kept = new ArrayList<>();
        for (StudioVisualCatalogEntry entry : entries) {
            if (entry.admitted()) {
                kept.add(entry);
            } else {
                System.err.println("[studio] catalog: dropped " + entry.catalogEntryId() + " from " + where
                        + ": it carries no claim read from a component artifact");
            }
        }
        return List.copyOf(kept);
    }

    /**
     * An earlier catalog version as restored from state, under the same load-time rule: its entries without a claim are
     * dropped and named in the log, and its {@code catalogHash} is computed from the entries it keeps, so what a selected
     * version serves and its hash agree. A version that held none is returned unchanged.
     */
    private StudioCatalogSnapshot withClaimsOnly(String tenant, String fileId, StudioCatalogSnapshot snapshot) {
        List<StudioVisualCatalogEntry> kept = withClaimsOnly("tenant " + tenant + " catalog version " + fileId, snapshot.entries());
        if (kept.size() == snapshot.entries().size()) {
            return snapshot;
        }
        return new StudioCatalogSnapshot(snapshot.tenantId(), response(kept).catalogHash(), kept, snapshot.diagnosticArtifacts());
    }

    /**
     * Catalog source (LLD-fabric-studio-api, What a catalog entry is): a tenant's catalog starts as the jars with a
     * catalog manifest scanned in the asset root, each decorated by any package visual manifest that names it, and grows
     * by admission. Fabric ships no built-in entries: with no asset root, or no jar in it carrying a manifest, a
     * tenant's catalog starts empty.
     */
    private List<StudioVisualCatalogEntry> catalogEntries(String tenantId) {
        List<StudioVisualCatalogEntry> scanned = scanCatalogManifests(tenantId);
        for (String orphan : packageVisualAssets.orphanedVisuals(scanned, assetRoot)) {
            System.err.println("[studio] package visuals: skipped " + orphan
                    + ": no admitted or scanned jar provides that catalog entry, and visuals never create one");
        }
        return packageVisualAssets.decorate(scanned, assetRoot);
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
        Map<String, Object> dynamicComposition = dynamicComposition(
                "COMPONENT",
                List.of(),
                rawNeeds,
                rawOptionalNeeds(claim),
                entry.componentShapeProfile());
        return new StudioVisualCatalogEntry(
                entry.artifact().coordinates(),
                entry.claimDescriptor().claimHash(),
                entry.artifact().sha256(),
                visual(category, fallbackKind, capabilities, requiredCapabilities, optionalCapabilitiesFromClaim(claim)),
                dynamicComposition,
                Map.of(),
                List.of(),
                scannedManifest(entry));
    }

    /**
     * Reads the manifest text of a scanned jar, so the visual entry keeps the exact claim document the jar carries.
     *
     * @param entry a catalog entry produced by {@link CatalogScanner}, which records the jar path.
     * @return the manifest text.
     * @throws IllegalStateException when the jar path is unknown or the jar can no longer be read.
     */
    private static String scannedManifest(CatalogEntry entry) {
        if (entry.localPath() == null) {
            throw new IllegalStateException("scanned catalog entry " + entry.artifact().coordinates() + " has no jar path");
        }
        try {
            return new String(CatalogScanner.readCatalogManifest(entry.localPath())
                    .orElseThrow(() -> new IllegalStateException(entry.localPath() + " no longer has a catalog manifest")),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read the catalog manifest of " + entry.localPath(), ex);
        }
    }

    /**
     * Parses {@code claim.dependencies.needs} strings (format
     * {@code <cap>[@<version>][?<params>]}) into bare capability names so
     * the visual manifest can emit DEPENDENCY ports alongside its OFFER
     * ports, using DCP's dependency-name rule like the matcher does.
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

    /**
     * Parser: the capability names of {@code claim.dependencies.optional}, for the visual manifest's optional
     * DEPENDENCY ports. Names that are also needs are dropped, so a port is never both required and optional (DCP's
     * claim validator rejects such claims; this keeps an unvalidated fixture from producing duplicate port ids).
     */
    private List<String> optionalCapabilitiesFromClaim(Claim claim) {
        if (claim == null || claim.dependencies() == null) {
            return List.of();
        }
        List<String> required = requiredCapabilitiesFromClaim(claim);
        return claim.dependencies().optional().stream()
                .map(StudioCatalogService::capabilityNameFromDependencyUri)
                .filter(name -> name != null && !name.isBlank() && !required.contains(name))
                .distinct()
                .toList();
    }

    /** Reader: the raw {@code claim.dependencies.optional} strings, qualifiers intact, for the dynamic-composition block. */
    private static List<String> rawOptionalNeeds(Claim claim) {
        return claim == null || claim.dependencies() == null ? List.of() : List.copyOf(claim.dependencies().optional());
    }

    /** Parser: a dependency URI's capability name, by DCP's own rule ({@link com.unfurl.dcp.claim.Dependencies#capabilityOf}). */
    private static String capabilityNameFromDependencyUri(String dep) {
        return com.unfurl.dcp.claim.Dependencies.capabilityOf(dep);
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

    /**
     * Build a visual descriptor that emits one port per offered AND one
     * per required capability. OFFER ports anchor to the right ({@code
     * SQUARE_SOCKET}); DEPENDENCY ports anchor to the left ({@code
     * SQUARE_PLUG}) so the renderer naturally separates "what I provide"
     * from "what I need" along opposite faces of the component group.
     * Both lists feed the hover-preview compatibility matcher in
     * {@link #connectionCandidates}. Optional dependencies get DEPENDENCY
     * ports too, marked {@code optional: true}, so a selected provider
     * still connects to them while the component stays valid without one.
     */
    private Map<String, Object> visual(
            String category,
            String fallbackKind,
            List<String> offeredCapabilities,
            List<String> requiredCapabilities,
            List<String> optionalCapabilities
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
        for (String capability : optionalCapabilities) {
            ports.add(Map.of(
                    "id", "need-" + capability.replace('.', '-'),
                    "mapsTo", "claim.dependencies." + capability,
                    "kind", "DEPENDENCY",
                    "optional", true,
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
     * Builder: creates Studio's dynamic-composition metadata block with optional raw
     * dependency strings from catalog manifests or admitted claims.
     */
    private Map<String, Object> dynamicComposition(
            String dcpType,
            List<String> compatibleDescendants,
            List<String> rawNeeds
    ) {
        return dynamicComposition(dcpType, compatibleDescendants, rawNeeds, List.of(), null);
    }

    /**
     * Builder: creates Studio's dynamic-composition metadata block with optional raw
     * dependency strings and optional deployment shape profile metadata from catalog
     * manifests. The shape profile is kept as primitive JSON values so persisted Studio
     * state remains a portable read model rather than a Java-domain serialization.
     * {@code rawNeeds} and {@code rawOptionalNeeds} hold a claim's {@code dependencies.needs} and
     * {@code dependencies.optional} strings with their qualifiers; each key is written only when non-empty.
     */
    private Map<String, Object> dynamicComposition(
            String dcpType,
            List<String> compatibleDescendants,
            List<String> rawNeeds,
            List<String> rawOptionalNeeds,
            ComponentShapeProfile shapeProfile
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
        if (rawOptionalNeeds != null && !rawOptionalNeeds.isEmpty()) {
            dynamic.put("rawOptionalNeeds", List.copyOf(rawOptionalNeeds));
        }
        if (shapeProfile != null) {
            dynamic.put("componentShapeProfile", Map.of(
                    "defaultShape", shapeProfile.defaultShape().name(),
                    "supportedShapes", shapeProfile.supportedShapes().stream()
                            .map(DeploymentShape::name)
                            .sorted()
                            .toList()));
        }
        return Map.copyOf(dynamic);
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

    private StudioDraftSession authoringSession(
            String tenantId,
            String assemblyId,
            String sessionId,
            String catalogFileId,
            String displayName
    ) {
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
                        "",
                        "",
                        "",
                        "",
                        "authoring-agent",
                        "Authoring Agent",
                        catalogFileId,
                        displayName)).session();
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
     * Factory: the summary of an assembly Fabric has no record of (LLD-fabric-studio-api, Assembly records): its target
     * application named after it, and no deployment target, shape, needs or candidate, because none was chosen.
     */
    private StudioAssemblySummary defaultAssemblySummary(String tenantId, String assemblyId) {
        String assembly = normalizeAssembly(assemblyId);
        return new StudioAssemblySummary(
                normalizeTenant(tenantId),
                assembly,
                assembly,
                "",
                "",
                "",
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
                session.intentLog(),
                session.catalogFileId(),
                session.displayName(),
                session.sessionType(),
                session.status(),
                session.createdAt(),
                session.updatedAt(),
                session.lastOpenedAt(),
                session.compileRecords());
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
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries =
                entriesByTenant.computeIfAbsent(tenant, this::catalogEntries);
        return rejectIntentAgainstCatalog(tenant, intentType, payload, entries);
    }

    /**
     * Guard strategy: validates catalog-selecting intents against the exact
     * catalog snapshot that produced the proposal or draft mutation. This keeps
     * Foundry proposal validation reproducible for historical catalog versions
     * while the public overload still uses the tenant's active catalog.
     */
    private Optional<StudioIntentResponse> rejectIntentAgainstCatalog(
            String tenantId,
            String intentType,
            Map<String, Object> payload,
            List<StudioVisualCatalogEntry> catalogEntries
    ) {
        String canonicalType = canonicalStudioIntentType(intentType);
        String key;
        if ("ADD_COMPONENT".equals(canonicalType)) {
            key = "catalogEntryId";
        } else if ("REPLACE_COMPONENT".equals(canonicalType)) {
            key = "newCatalogEntryId";
        } else {
            return Optional.empty();
        }
        String requestedEntryId = stringValue(payload.get(key), "");
        if (requestedEntryId.isBlank()) {
            return Optional.of(StudioIntentResponse.invalid(
                    "CATALOG_ENTRY_REQUIRED",
                    canonicalType + " requires a non-blank " + key));
        }
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> entries = catalogEntries == null ? List.of() : catalogEntries;
        Optional<StudioVisualCatalogEntry> requested = entries.stream()
                .filter(entry -> requestedEntryId.equals(entry.catalogEntryId()))
                .findFirst();
        if (requested.isEmpty()) {
            return Optional.of(StudioIntentResponse.invalid(
                    "CATALOG_ENTRY_NOT_FOUND",
                    "catalog entry '" + requestedEntryId + "' is not registered in tenant '" + tenant + "'"));
        }
        // A draft never gains a component that could not compile (LLD-fabric-studio-api, Sessions).
        if (!requested.get().admitted()) {
            return Optional.of(StudioIntentResponse.invalid(
                    "CATALOG_ENTRY_NOT_ADMITTED",
                    "catalog entry '" + requestedEntryId + "' carries no claim read from a component artifact; admit its jar"));
        }
        return Optional.empty();
    }

    private void updateAssemblyRevision(StudioDraftSession session) {
        Map<String, StudioAssemblySummary> assemblies = assembliesByTenant.computeIfAbsent(session.tenantId(), ignored -> new ConcurrentHashMap<>());
        StudioAssemblySummary previous = assemblies.get(session.assemblyId());
        assemblies.put(session.assemblyId(), new StudioAssemblySummary(
                session.tenantId(),
                session.assemblyId(),
                previous == null ? "" : previous.targetApplicationName(),
                previous == null ? "" : previous.defaultDeploymentTarget(),
                session.needsId(),
                previous == null ? "" : previous.deploymentShape(),
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

    /**
     * Registry command: materializes the current tenant catalog as an immutable
     * `CATALOG` file version and stores the matching snapshot for later draft
     * binding.
     *
     * <p>Pattern: versioned read-model projector. Repeated calls for identical
     * catalog bytes reuse the existing file row, while changed catalog content
     * receives the next version for logical file id `tenant-catalog`.
     */
    private StudioFileRecord ensureCatalogFile(String tenantId, List<StudioVisualCatalogEntry> entries, String title) {
        String tenant = normalizeTenant(tenantId);
        List<StudioVisualCatalogEntry> safeEntries = entries == null ? List.of() : List.copyOf(entries);
        StudioCatalogVisualsResponse catalog = response(safeEntries);
        StudioCatalogSnapshot snapshot = new StudioCatalogSnapshot(tenant, catalog.catalogHash(), catalog.entries(), List.of());
        byte[] bytes = jsonBytes(snapshot);
        String sha = sha256(bytes);
        Map<String, StudioFileRecord> tenantFiles =
                filesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>());
        Optional<StudioFileRecord> existing = tenantFiles.values().stream()
                .filter(file -> "CATALOG".equalsIgnoreCase(file.fileType()))
                .filter(file -> sha.equals(file.sha256()))
                .findFirst();
        if (existing.isPresent()) {
            boolean snapshotMissing = !catalogSnapshotsByTenant
                    .getOrDefault(tenant, Map.of())
                    .containsKey(existing.get().fileId());
            catalogSnapshotsByTenant
                    .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                    .put(existing.get().fileId(), snapshot);
            if (snapshotMissing) {
                persist();
            }
            return existing.get();
        }

        int version = nextFileVersion(tenant, "tenant-catalog");
        String fileId = "file-" + UUID.randomUUID();
        StudioFileRecord file = new StudioFileRecord(
                fileId,
                tenant,
                "tenant-catalog",
                version,
                "catalog-v" + version + "-" + shortHash(sha) + ".json",
                stringValue(title, "Tenant Catalog v" + version),
                "/studio/tenants/" + tenant + "/files/" + fileId + "/content?sha256=" + sha,
                "CATALOG",
                "application/json",
                sha,
                Instant.now());
        tenantFiles.put(file.fileId(), file);
        catalogSnapshotsByTenant
                .computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(file.fileId(), snapshot);
        persist();
        return file;
    }

    /**
     * Registry query: resolves the latest catalog file or a caller-selected
     * catalog file id within the current tenant boundary.
     */
    private StudioFileRecord selectedCatalogFile(String tenantId, String requestedCatalogFileId) {
        String tenant = normalizeTenant(tenantId);
        ensureCatalogFile(tenant, entriesByTenant.computeIfAbsent(tenant, this::catalogEntries), "");
        Map<String, StudioFileRecord> tenantFiles = filesByTenant.getOrDefault(tenant, Map.of());
        if (requestedCatalogFileId != null && !requestedCatalogFileId.isBlank()) {
            StudioFileRecord file = tenantFiles.get(requestedCatalogFileId.trim());
            if (file == null || !"CATALOG".equalsIgnoreCase(file.fileType())) {
                throw new IllegalArgumentException("catalog file not found for tenant: " + requestedCatalogFileId);
            }
            catalogSnapshotForFile(tenant, file.fileId());
            return file;
        }
        return tenantFiles.values().stream()
                .filter(file -> "CATALOG".equalsIgnoreCase(file.fileType()))
                .max(Comparator
                        .comparing(StudioFileRecord::createdAt)
                        .thenComparingInt(StudioFileRecord::version)
                        .thenComparing(StudioFileRecord::fileId))
                .orElse(null);
    }

    /**
     * Registry query: loads the immutable catalog snapshot for a tenant-owned
     * catalog file id.
     */
    private StudioCatalogSnapshot catalogSnapshotForFile(String tenantId, String fileId) {
        String tenant = normalizeTenant(tenantId);
        StudioCatalogSnapshot snapshot = catalogSnapshotsByTenant
                .getOrDefault(tenant, Map.of())
                .get(fileId == null ? "" : fileId.trim());
        if (snapshot == null) {
            throw new IllegalArgumentException("catalog snapshot file not found for tenant: " + fileId);
        }
        return snapshot;
    }

    /**
     * Registry command: stores a generated artifact as a new file version and
     * links it to the producing session.
     */
    private StudioFileRecord registerArtifactFile(
            StudioDraftSession session,
            StudioExportArtifact artifact,
            String fileType,
            String role
    ) {
        if (artifact == null) {
            return null;
        }
        StudioFileRecord file = registerFile(
                session.tenantId(),
                session.sessionId() + ":" + artifact.artifactId(),
                fileNameFromArtifact(artifact),
                displayTitleFromArtifact(artifact),
                fileType,
                artifact.mediaType(),
                artifact.sha256(),
                artifact.url());
        linkFileToSession(session.tenantId(), session.sessionId(), artifact.artifactId(), file.fileId(), role);
        return file;
    }

    /**
     * Registry command: records all successful compile outputs as session-linked
     * file versions.
     */
    private void registerCompileFiles(
            StudioDraftSession session,
            StudioExportArtifact contract,
            StudioExportArtifact profile,
            StudioExportArtifact signed,
            List<StudioExportArtifact> supportArtifacts,
            List<StudioExportArtifact> diagnosticArtifacts
    ) {
        registerArtifactFile(session, contract, "EXPORT", "OUTPUT");
        registerArtifactFile(session, profile, "EXPORT", "OUTPUT");
        registerArtifactFile(session, signed, "SIGNED_EXPORT", "OUTPUT");
        for (StudioExportArtifact artifact : supportArtifacts == null ? List.<StudioExportArtifact>of() : supportArtifacts) {
            registerArtifactFile(session, artifact, "SUPPORT", "OUTPUT");
        }
        for (StudioExportArtifact artifact : diagnosticArtifacts == null ? List.<StudioExportArtifact>of() : diagnosticArtifacts) {
            registerArtifactFile(session, artifact, "DIAGNOSTIC", "DIAGNOSTIC");
        }
    }

    /**
     * Registry command: inserts one immutable file-version row for generated
     * Studio artifacts.
     */
    private StudioFileRecord registerFile(
            String tenantId,
            String logicalFileId,
            String fileName,
            String fileTitle,
            String fileType,
            String mediaType,
            String sha256,
            String filePath
    ) {
        String tenant = normalizeTenant(tenantId);
        String logical = stringValue(logicalFileId, "file");
        int version = nextFileVersion(tenant, logical);
        StudioFileRecord file = new StudioFileRecord(
                "file-" + UUID.randomUUID(),
                tenant,
                logical,
                version,
                fileName,
                fileTitle,
                filePath,
                fileType,
                mediaType,
                sha256,
                Instant.now());
        filesByTenant.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(file.fileId(), file);
        return file;
    }

    /**
     * Registry command: creates the tenant-scoped association row between a
     * session/correlation id and a file id.
     */
    private void linkFileToSession(String tenantId, String sessionId, String correlationId, String fileId, String role) {
        String tenant = normalizeTenant(tenantId);
        if (!filesByTenant.getOrDefault(tenant, Map.of()).containsKey(fileId)) {
            throw new IllegalArgumentException("session file link references unknown tenant file: " + fileId);
        }
        List<StudioSessionFileLink> existing =
                sessionFileLinksByTenant.getOrDefault(tenant, List.of());
        boolean duplicate = existing.stream().anyMatch(link ->
                link.sessionId().equals(sessionId == null ? "" : sessionId)
                        && link.correlationId().equals(correlationId == null ? "" : correlationId)
                        && link.fileId().equals(fileId)
                        && link.role().equals(role == null ? "" : role));
        if (duplicate) {
            return;
        }
        List<StudioSessionFileLink> updated = new ArrayList<>(existing);
        updated.add(new StudioSessionFileLink(
                "link-" + UUID.randomUUID(),
                tenant,
                sessionId,
                correlationId,
                fileId,
                role,
                Instant.now()));
        sessionFileLinksByTenant.put(tenant, List.copyOf(updated));
    }

    /**
     * Session projector: updates a session's lifecycle status while preserving
     * intent/collaboration and provenance fields.
     */
    private void markSessionStatus(StudioDraftSession session, String status) {
        Instant now = Instant.now();
        StudioDraftSession updated = new StudioDraftSession(
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
                session.collaborators(),
                session.intentLog(),
                session.catalogFileId(),
                session.displayName(),
                session.sessionType(),
                stringValue(status, session.status()),
                session.createdAt(),
                now,
                now,
                session.compileRecords());
        putSession(updated);
        publishSessionEvent(updated);
    }

    /**
     * Registry query helper: returns the file ids linked to a session and/or
     * correlation id, or null when no link filter was requested.
     */
    private Set<String> linkedFileIds(String tenantId, String sessionId, String correlationId) {
        String session = sessionId == null ? "" : sessionId.trim();
        String correlation = correlationId == null ? "" : correlationId.trim();
        if (session.isBlank() && correlation.isBlank()) {
            return null;
        }
        return sessionFileLinksByTenant
                .getOrDefault(normalizeTenant(tenantId), List.of())
                .stream()
                .filter(link -> session.isBlank() || session.equals(link.sessionId()))
                .filter(link -> correlation.isBlank() || correlation.equals(link.correlationId()))
                .map(StudioSessionFileLink::fileId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * History projector helper: counts linked files by session id for the
     * tenant-level history endpoint.
     */
    private Map<String, Long> linkedFileCountBySession(String tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (StudioSessionFileLink link : sessionFileLinksByTenant.getOrDefault(normalizeTenant(tenantId), List.of())) {
            if (!link.sessionId().isBlank()) {
                counts.merge(link.sessionId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * Download projector: resolves stored file rows back to bytes for file types
     * whose content remains owned by Studio.
     */
    private Optional<StudioAssetContent> contentForFileRecord(String tenantId, StudioFileRecord file) {
        if ("CATALOG".equalsIgnoreCase(file.fileType())) {
            StudioCatalogSnapshot snapshot = catalogSnapshotsByTenant
                    .getOrDefault(normalizeTenant(tenantId), Map.of())
                    .get(file.fileId());
            if (snapshot == null) {
                return Optional.empty();
            }
            byte[] bytes = jsonBytes(snapshot);
            return Optional.of(new StudioAssetContent(bytes, "application/json", sha256(bytes)));
        }
        String artifactId = artifactIdFromPath(file.filePath(), "/diagnostic-artifacts/");
        if (!artifactId.isBlank()) {
            return diagnosticArtifactContent(tenantId, artifactId, file.sha256());
        }
        artifactId = artifactIdFromPath(file.filePath(), "/exports/");
        if (!artifactId.isBlank()) {
            return exportArtifactContent(tenantId, artifactId, file.sha256());
        }
        return Optional.empty();
    }

    /**
     * Version allocator: returns the next immutable version number for a logical
     * tenant file id.
     */
    private int nextFileVersion(String tenantId, String logicalFileId) {
        String tenant = normalizeTenant(tenantId);
        String logical = stringValue(logicalFileId, "file");
        return filesByTenant
                .getOrDefault(tenant, Map.of())
                .values()
                .stream()
                .filter(file -> logical.equals(file.logicalFileId()))
                .mapToInt(StudioFileRecord::version)
                .max()
                .orElse(0) + 1;
    }

    /**
     * JSON codec helper: serializes registry-owned content with the same mapper
     * used by Studio HTTP responses.
     */
    private byte[] jsonBytes(Object value) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to serialize Studio file content", ex);
        }
    }

    /**
     * Naming helper: derives a human-readable session title when the caller did
     * not supply one.
     */
    private String draftDisplayName(String requestedDisplayName, String assemblyId, StudioFileRecord catalogFile) {
        if (requestedDisplayName != null && !requestedDisplayName.isBlank()) {
            return requestedDisplayName.trim();
        }
        String catalogTitle = catalogFile == null ? "Current Catalog" : catalogFile.fileTitle();
        return normalizeAssembly(assemblyId) + " - " + catalogTitle;
    }

    /**
     * URL helper: extracts a stable file name from Studio artifact URLs.
     */
    private String fileNameFromArtifact(StudioExportArtifact artifact) {
        String url = artifact == null ? "" : artifact.url();
        int marker = url.indexOf("fileName=");
        if (marker >= 0) {
            return url.substring(marker + "fileName=".length());
        }
        return artifact == null ? "artifact" : artifact.artifactId();
    }

    /**
     * Naming helper: converts generated artifact file names into concise display
     * titles for history and artifact panes.
     */
    private String displayTitleFromArtifact(StudioExportArtifact artifact) {
        String fileName = fileNameFromArtifact(artifact);
        String withoutExtension = fileName.replaceFirst("\\.[^.]+$", "");
        return labelFromNodeId("artifact." + withoutExtension.replace('_', '-').replace('.', '-'));
    }

    /**
     * URL helper: extracts a tenant artifact id from a Studio file path.
     */
    private String artifactIdFromPath(String path, String marker) {
        String value = path == null ? "" : path;
        int start = value.indexOf(marker);
        if (start < 0) {
            return "";
        }
        String remainder = value.substring(start + marker.length());
        int slash = remainder.indexOf('/');
        return slash < 0 ? "" : remainder.substring(0, slash);
    }

    /**
     * Hash display helper: creates compact but stable catalog file names.
     */
    private String shortHash(String sha) {
        String value = sha == null ? "" : sha.replace("sha256:", "");
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static Path defaultAssetRoot() {
        String configured = System.getProperty("unfurl.studio.asset.root");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("UNFURL_STUDIO_ASSET_ROOT");
        }
        // No configured root means no scanned catalog: entries come from admission only (LLD-fabric-studio-api).
        return configured == null || configured.isBlank() ? null : Path.of(configured);
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
            InvalidDraft invalid,
            Map<String, String> catalogEntryIdsByCoordinates
    ) {
        /**
         * Factory for valid candidate builds.
         *
         * @param catalogEntryIdsByCoordinates each selected entry's artifact coordinates to the draft catalog entry it came
         *        from, so answers keyed by coordinates can be placed on the draft's nodes.
         */
        static CandidateBuild valid(CompositionCandidate candidate, List<String> warnings,
                                    Map<String, String> catalogEntryIdsByCoordinates) {
            return new CandidateBuild(candidate, warnings == null ? List.of() : List.copyOf(warnings), null,
                    Map.copyOf(catalogEntryIdsByCoordinates));
        }

        /**
         * Factory for invalid candidate builds.
         */
        static CandidateBuild invalid(InvalidDraft invalid) {
            return new CandidateBuild(null, List.of(), invalid, Map.of());
        }
    }

    /**
     * Value object: compile/export artifact bytes before they are registered in the
     * tenant export or diagnostic stores.
     */
    private record CompiledArtifacts(
            CompiledContract contract,
            byte[] rootContractBytes,
            byte[] compiledEnvelopeBytes,
            byte[] profileBytes
    ) {
        /**
         * Invariant constructor: clones mutable byte arrays.
         */
        CompiledArtifacts {
            rootContractBytes = rootContractBytes == null ? new byte[0] : rootContractBytes.clone();
            compiledEnvelopeBytes = compiledEnvelopeBytes == null ? new byte[0] : compiledEnvelopeBytes.clone();
            profileBytes = profileBytes == null ? new byte[0] : profileBytes.clone();
        }

        /**
         * Defensive accessor: returns a copy of the primary root DCP contract bytes.
         */
        @Override
        public byte[] rootContractBytes() {
            return rootContractBytes.clone();
        }

        /**
         * Defensive accessor: returns a copy of the full Fabric compiler diagnostic envelope.
         */
        @Override
        public byte[] compiledEnvelopeBytes() {
            return compiledEnvelopeBytes.clone();
        }

        /**
         * Defensive accessor: returns a copy of the substrate profile export bytes.
         */
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
     * Value object: user-facing invalid draft status for compile/resolve responses, with the typed causes of a
     * {@code NO_MATCH} (empty for any other reason).
     */
    private record InvalidDraft(
            String reason,
            String details,
            List<MatchFailureReason> reasons
    ) {
        /**
         * Invariant constructor: normalizes absent text so JSON responses are stable.
         */
        InvalidDraft {
            reason = reason == null || reason.isBlank() ? "INVALID_DRAFT" : reason;
            details = details == null ? "" : details;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        /** An invalid draft without typed causes. */
        InvalidDraft(String reason, String details) {
            this(reason, details, List.of());
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
