package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.CatalogScanReport;
import com.unfurl.fabric.catalog.CatalogScanner;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StudioCatalogService {
    private final Map<String, List<StudioVisualCatalogEntry>> entriesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioLayoutState>> layoutsByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioDraftSession>> sessionsByTenant = new ConcurrentHashMap<>();
    private final StudioStateStore store;
    private final Path assetRoot;
    private final StudioSessionEventBus eventBus;
    private final StudioPackageVisualAssets packageVisualAssets = new StudioPackageVisualAssets();

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
                ? "unfurl-flow"
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
                        ? "Unfurl Flow Assembly"
                        : summary.targetApplicationName() + " Assembly",
                "MODULE",
                "ASSEMBLY",
                "",
                childNodes.stream().flatMap(node -> node.capabilities().stream()).distinct().sorted().toList(),
                childNodeIds,
                false));
        nodes.addAll(childNodes);

        List<StudioSubstratePort> substratePorts = deriveSubstratePorts(childNodes, entries);
        List<StudioPortConnectionEdge> connections = derivePortConnections(childNodes, entries, substratePorts);

        return new StudioDynamicDcpProjection(
                tenant,
                assembly,
                "DYNAMIC",
                rootNodeId,
                focusNodeId,
                nodes,
                edges,
                substratePorts,
                connections,
                List.of());
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
        publishSessionEvent(session);
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
        // Fall-through order:
        //   1. Real META-INF/unfurl-catalog.yaml entries via fabric's CatalogScanner
        //      (production format used by the portfolio JARs)
        //   2. META-INF/unfurl-studio-visuals.json entries via StudioPackageVisualAssets
        //      (legacy visual-only fixtures: validation-service.glb, storage-s3.glb)
        //   3. Hardcoded bundledFixtureEntries (final fallback when nothing else is staged)
        List<StudioVisualCatalogEntry> manifestEntries = scanCatalogManifests();
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
    private List<StudioVisualCatalogEntry> scanCatalogManifests() {
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
        Map<String, Object> dynamicComposition = rawNeeds.isEmpty()
                ? Map.of()
                : Map.of("rawNeeds", rawNeeds);
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

    private Map<String, Object> dynamicComposition(String dcpType, List<String> compatibleDescendants) {
        return Map.of(
                "compositionMode", "DYNAMIC",
                "dcpType", dcpType,
                "compatibleDescendants", compatibleDescendants,
                "selectionPolicy", Map.of("strategy", "POLICY_DRIVEN", "rules", List.of()),
                "binding", Map.of("mode", "LATE_BOUND", "validation", "REQUIRED_BEFORE_ACTIVATION"));
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

    private String candidateAfterIntent(String currentCandidateId, StudioIntentRequest request, Map<String, Object> payload) {
        if ("REPLACE_COMPONENT".equals(request.type)) {
            return stringValue(payload.get("newCatalogEntryId"), currentCandidateId);
        }
        if ("ADD_COMPONENT".equals(request.type)) {
            return stringValue(payload.get("catalogEntryId"), currentCandidateId);
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
        return configured == null || configured.isBlank() ? StudioFixtureAssets.assetRoot() : Path.of(configured);
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
