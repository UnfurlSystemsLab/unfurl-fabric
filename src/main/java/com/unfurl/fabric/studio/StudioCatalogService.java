package com.unfurl.fabric.studio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StudioCatalogService {
    private final Map<String, List<StudioVisualCatalogEntry>> entriesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioLayoutState>> layoutsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioDraftSession>> sessionsByTenant = new ConcurrentHashMap<>();
    private final StudioStateStore store;
    private final Path assetRoot;

    public StudioCatalogService() {
        this(null, defaultAssetRoot());
    }

    public StudioCatalogService(StudioStateStore store) {
        this(store, defaultAssetRoot());
    }

    public StudioCatalogService(StudioStateStore store, Path assetRoot) {
        this.store = store;
        this.assetRoot = assetRoot == null ? null : assetRoot.toAbsolutePath().normalize();
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
        return response(entries);
    }

    public StudioCatalogAdmissionResponse admit(String tenantId, StudioCatalogAdmissionRequest request) {
        String tenant = normalizeTenant(tenantId);
        StudioCatalogAdmissionRequest safeRequest = request == null
                ? new StudioCatalogAdmissionRequest("assembly-demo", List.of())
                : request;
        List<StudioVisualCatalogEntry> entries = new ArrayList<>(entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries));
        List<StudioClaimVerificationResult> results = new ArrayList<>();

        for (StudioComponentArtifactDraft artifact : safeRequest.artifacts()) {
            if (artifact.fileName() == null || artifact.fileName().isBlank()) {
                results.add(new StudioClaimVerificationResult("", "REJECTED", "", "", List.of("fileName is required")));
                continue;
            }
            if (!artifact.fileName().endsWith(".jar") && !artifact.fileName().endsWith(".yaml") && !artifact.fileName().endsWith(".yml")) {
                results.add(new StudioClaimVerificationResult(artifact.fileName(), "REJECTED", "", "",
                        List.of("unsupported artifact type")));
                continue;
            }
            String entryId = "uploaded:" + artifact.fileName().replace('\\', '/');
            String claimHash = sha256("claim:" + tenant + ":" + safeRequest.assemblyId() + ":" + artifact.fileName());
            String artifactSha = artifact.sha256() == null || artifact.sha256().isBlank()
                    ? sha256("artifact:" + artifact.fileName())
                    : artifact.sha256();
            StudioVisualCatalogEntry entry = new StudioVisualCatalogEntry(
                    entryId,
                    claimHash,
                    artifactSha,
                    fallbackVisual("COMPONENT"),
                    dynamicComposition("COMPONENT", List.of()),
                    Map.of("visualManifestHash", sha256("visual:" + entryId), "assets", List.of()),
                    List.of());
            entries.removeIf(existing -> existing.catalogEntryId().equals(entryId));
            entries.add(entry);
            results.add(new StudioClaimVerificationResult(artifact.fileName(), "VERIFIED", entryId, claimHash, List.of()));
        }

        entries.sort(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId));
        entriesByTenant.put(tenant, List.copyOf(entries));
        persist();
        boolean allVerified = !results.isEmpty()
                && results.stream().allMatch(result -> "VERIFIED".equals(result.status()));
        return new StudioCatalogAdmissionResponse(
                tenant,
                safeRequest.assemblyId(),
                allVerified ? "VERIFIED" : "REJECTED",
                results,
                response(entries));
    }

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
        String capability = safeRequest.targetApplicationName()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.|\\.$", "");
        if (capability.isBlank()) {
            capability = "application";
        }
        String yaml = """
                requiredCapabilities:
                  - capability: %s.run
                    capabilityVersion: ^1
                """.formatted(capability);
        List<String> warnings = safeRequest.fileNames().isEmpty()
                ? List.of("no target application files supplied; generated starter needs")
                : List.of();
        return new StudioNeedsExtractionResponse(
                tenant,
                assembly,
                needsId,
                safeRequest.targetApplicationName(),
                yaml,
                safeRequest.defaultDeploymentTarget(),
                warnings);
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
                ? "E-Commerce Platform"
                : summary.targetApplicationName();
        String rootNodeId = "company:" + slug(target);
        String focusNodeId = "assembly:" + slug(assembly);
        List<StudioVisualCatalogEntry> entries = entriesByTenant.computeIfAbsent(tenant, this::fixtureEntries);
        List<StudioDynamicDcpNode> childNodes = entries.stream()
                .sorted(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId))
                .map(this::dynamicNodeForEntry)
                .toList();
        List<String> childNodeIds = childNodes.stream().map(StudioDynamicDcpNode::nodeId).toList();
        List<StudioDynamicDcpEdge> edges = new ArrayList<>();
        edges.add(new StudioDynamicDcpEdge(rootNodeId, focusNodeId, "CONTAINS"));
        for (String childNodeId : childNodeIds) {
            edges.add(new StudioDynamicDcpEdge(focusNodeId, childNodeId, "CONTAINS"));
        }
        for (int i = 0; i < childNodeIds.size() - 1; i++) {
            edges.add(new StudioDynamicDcpEdge(childNodeIds.get(i), childNodeIds.get(i + 1), "REQUIRES"));
        }

        List<StudioDynamicDcpNode> nodes = new ArrayList<>();
        nodes.add(new StudioDynamicDcpNode(
                rootNodeId,
                target,
                "COMPANY",
                "PARENT",
                "",
                List.of(slug(target).replace('-', '.') + ".compose"),
                List.of(focusNodeId),
                false));
        nodes.add(new StudioDynamicDcpNode(
                focusNodeId,
                summary == null || summary.targetApplicationName().isBlank()
                        ? "Checkout Assembly"
                        : summary.targetApplicationName() + " Assembly",
                "MODULE",
                "ASSEMBLY",
                "",
                childNodes.stream().flatMap(node -> node.capabilities().stream()).distinct().sorted().toList(),
                childNodeIds,
                false));
        nodes.addAll(childNodes);

        return new StudioDynamicDcpProjection(
                tenant,
                assembly,
                "DYNAMIC",
                rootNodeId,
                focusNodeId,
                nodes,
                edges,
                List.of());
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
        return new StudioSaveDraftResponse("SAVED", saved, List.of());
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
        return new StudioCreateDraftCompositionResponse(session);
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
        return updated;
    }

    public synchronized StudioCompileDraftCandidateResponse compileCandidate(
            String tenantId,
            String assemblyId,
            String sessionId,
            StudioCompileDraftCandidateRequest request
    ) {
        StudioDraftSession session = draftSession(tenantId, assemblyId, sessionId);
        long expected = request == null ? session.sceneRevision() : request.expectedRevision();
        if (expected != session.sceneRevision()) {
            return new StudioCompileDraftCandidateResponse(
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
        }
        String candidateId = session.currentCandidateId().isBlank()
                ? "cand-" + slug(session.assemblyId()) + "-" + session.sceneRevision()
                : session.currentCandidateId();
        StudioExportArtifact contract = artifact("contract-" + session.sessionId(), "application/yaml");
        StudioExportArtifact profile = artifact("substrate-profile-" + session.sessionId(), "application/yaml");
        StudioExportArtifact signed = request != null && request.sign()
                ? artifact("signed-contract-" + session.sessionId(), "application/jose+json")
                : null;
        return new StudioCompileDraftCandidateResponse(
                "COMPILED",
                candidateId,
                contract,
                profile,
                signed,
                session.collaborators().size() > 1
                        ? List.of("compiled shared session with " + session.collaborators().size() + " active collaborators")
                        : List.of(),
                "",
                "",
                0,
                0);
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

    private StudioCatalogVisualsResponse response(List<StudioVisualCatalogEntry> entries) {
        return new StudioCatalogVisualsResponse(
                sha256(entries.stream().map(StudioVisualCatalogEntry::catalogEntryId).sorted().toList().toString()),
                entries);
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

    private List<StudioVisualCatalogEntry> fixtureEntries(String tenantId) {
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
                "E-Commerce Platform",
                "On-Prem Cluster",
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
        return Map.of(
                "fallbackShape", Map.of("kind", "CUBE", "category", category),
                "ports", offeredCapabilities.stream()
                        .map(capability -> Map.of(
                                "id", capability.replace('.', '-'),
                                "mapsTo", "claim.offers." + capability,
                                "kind", "OFFER",
                                "anchor", "right",
                                "connectorShape", "SQUARE_SOCKET"))
                        .toList(),
                "interactions", Map.of("draggable", true, "connectable", true, "inspectable", true));
    }

    private Map<String, Object> dynamicComposition(String dcpType, List<String> compatibleDescendants) {
        return Map.of(
                "compositionMode", "DYNAMIC",
                "dcpType", dcpType,
                "compatibleDescendants", compatibleDescendants,
                "selectionPolicy", Map.of("strategy", "POLICY_DRIVEN", "rules", List.of()),
                "binding", Map.of("mode", "LATE_BOUND", "validation", "REQUIRED_BEFORE_ACTIVATION"));
    }

    private Map<String, Object> visualIntegrity(String slug, String path) {
        return Map.of(
                "visualManifestHash", sha256("visual:" + slug),
                "assets", List.of(Map.of(
                        "assetId", slug + "-model",
                        "path", path,
                        "mediaType", mediaTypeForPath(path),
                        "sha256", sha256("asset:" + slug + ":" + path))));
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

    private String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "tenant-local";
        }
        return tenantId.trim();
    }

    private String normalizeAssembly(String assemblyId) {
        return assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
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

    private String candidateAfterIntent(String currentCandidateId, StudioIntentRequest request, Map<String, Object> payload) {
        if ("REPLACE_COMPONENT".equals(request.type)) {
            return stringValue(payload.get("newCatalogEntryId"), currentCandidateId);
        }
        if ("ADD_COMPONENT".equals(request.type)) {
            return stringValue(payload.get("catalogEntryId"), currentCandidateId);
        }
        return currentCandidateId == null ? "" : currentCandidateId;
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

    private StudioExportArtifact artifact(String artifactId, String mediaType) {
        String sha = sha256("artifact:" + artifactId);
        return new StudioExportArtifact(
                artifactId,
                mediaType,
                sha,
                "/studio/exports/" + artifactId + "?sha256=" + sha);
    }

    private static Path defaultAssetRoot() {
        String configured = System.getProperty("unfurl.studio.asset.root");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("UNFURL_STUDIO_ASSET_ROOT");
        }
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private String slug(String value) {
        String slug = value.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "local" : slug;
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
}
