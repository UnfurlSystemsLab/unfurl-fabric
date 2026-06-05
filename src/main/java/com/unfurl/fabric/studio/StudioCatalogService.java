package com.unfurl.fabric.studio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StudioCatalogService {
    private final Map<String, List<StudioVisualCatalogEntry>> entriesByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant = new ConcurrentHashMap<>();

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
                    Map.of("visualManifestHash", sha256("visual:" + entryId), "assets", List.of()),
                    List.of());
            entries.removeIf(existing -> existing.catalogEntryId().equals(entryId));
            entries.add(entry);
            results.add(new StudioClaimVerificationResult(artifact.fileName(), "VERIFIED", entryId, claimHash, List.of()));
        }

        entries.sort(Comparator.comparing(StudioVisualCatalogEntry::catalogEntryId));
        entriesByTenant.put(tenant, List.copyOf(entries));
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
        return new StudioDynamicDcpProjection(
                tenant,
                assembly,
                "DYNAMIC",
                rootNodeId,
                focusNodeId,
                List.of(
                        new StudioDynamicDcpNode(
                                rootNodeId,
                                target,
                                "COMPANY",
                                "PARENT",
                                "",
                                List.of("commerce.checkout", "commerce.fulfillment"),
                                List.of(focusNodeId),
                                false),
                        new StudioDynamicDcpNode(
                                focusNodeId,
                                "Checkout Assembly",
                                "MODULE",
                                "ASSEMBLY",
                                "",
                                List.of("order.create", "validate.order", "payment.authorize"),
                                List.of("component.validation-service", "component.storage-s3"),
                                false),
                        new StudioDynamicDcpNode(
                                "component.validation-service",
                                "Validation Service",
                                "COMPONENT",
                                "CHILD",
                                "com.unfurl:validation-service:1.1.0",
                                List.of("validate.order", "validate.payment", "validate.inventory"),
                                List.of("component.customer-policy-validator", "component.fraud-validator"),
                                true),
                        new StudioDynamicDcpNode(
                                "component.storage-s3",
                                "S3 Storage",
                                "COMPONENT",
                                "CHILD",
                                "com.unfurl:storage-s3:1.2.0",
                                List.of("storage.put"),
                                List.of("component.azure-blob", "component.minio-storage"),
                                true)),
                List.of(
                        new StudioDynamicDcpEdge(rootNodeId, focusNodeId, "CONTAINS"),
                        new StudioDynamicDcpEdge(focusNodeId, "component.validation-service", "CONTAINS"),
                        new StudioDynamicDcpEdge(focusNodeId, "component.storage-s3", "CONTAINS"),
                        new StudioDynamicDcpEdge("component.validation-service", "component.storage-s3", "REQUIRES")),
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
        return new StudioSaveDraftResponse("SAVED", saved, List.of());
    }

    private StudioCatalogVisualsResponse response(List<StudioVisualCatalogEntry> entries) {
        return new StudioCatalogVisualsResponse(
                sha256(entries.stream().map(StudioVisualCatalogEntry::catalogEntryId).sorted().toList().toString()),
                entries);
    }

    private List<StudioVisualCatalogEntry> fixtureEntries(String tenantId) {
        return List.of(new StudioVisualCatalogEntry(
                "com.unfurl:validation-service:1.1.0",
                sha256("claim:" + tenantId + ":validation-service"),
                sha256("artifact:validation-service"),
                fallbackVisual("APPLICATION"),
                Map.of("visualManifestHash", sha256("visual:validation-service"), "assets", List.of()),
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
}
