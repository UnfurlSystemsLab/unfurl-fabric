package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Adapter / Facade: exposes selected Fabric Studio operations as Foundry-compatible
 * HTTP tools. The gateway does not implement authoring behavior locally; it converts
 * tool arguments into existing Studio DTOs and delegates all governed state changes
 * to {@link StudioCatalogService}.
 */
public final class StudioToolGateway {
    public static final String DEFAULT_TENANT_ID = "tenant-local";
    public static final String DEFAULT_ASSEMBLY_ID = "assembly-demo";

    private final StudioCatalogService service;
    private final ObjectMapper mapper;

    /**
     * Constructor: creates a gateway over the Studio service facade and JSON mapper
     * used for typed argument conversion.
     */
    public StudioToolGateway(StudioCatalogService service, ObjectMapper mapper) {
        this.service = service == null ? new StudioCatalogService() : service;
        this.mapper = mapper == null ? StudioJson.mapper() : mapper;
    }

    /**
     * Dispatcher: executes one Foundry-style tool call and returns a structured
     * PASS/GAP/ERROR result without bypassing Studio validation.
     */
    public StudioToolCallResult execute(StudioToolCallRequest request) {
        if (request == null) {
            return StudioToolCallResult.failure("FABRIC_TOOL_MALFORMED", "tool call request is required");
        }
        String toolName = request.toolName();
        if (toolName == null || toolName.isBlank()) {
            return StudioToolCallResult.failure("FABRIC_TOOL_MALFORMED", "toolName is required");
        }
        try {
            return switch (toolName) {
                case "fabric.catalog-admit" -> catalogAdmit(toolName, request.arguments());
                case "fabric.catalog-verify" -> catalogVerify(toolName, request.arguments());
                case "fabric.assembly-create" -> assemblyCreate(toolName, request.arguments());
                case "fabric.needs-extract" -> needsExtract(toolName, request.arguments());
                case "fabric.session-start" -> sessionStart(toolName, request.arguments());
                case "fabric.authoring-converse" -> authoringConverse(toolName, request.arguments());
                case "fabric.session-intent-apply" -> sessionIntentApply(toolName, request.arguments());
                case "fabric.dynamic-dcp-project" -> dynamicDcpProject(toolName, request.arguments());
                case "fabric.deployment-resolve" -> deploymentResolve(toolName, request.arguments());
                case "fabric.candidate-compile" -> candidateCompile(toolName, request.arguments());
                case "fabric.export-download" -> exportDownload(toolName, request.arguments());
                default -> gap(toolName, "unsupported Fabric Studio tool: " + toolName, Map.of(
                        "supportedTools", supportedTools()));
            };
        } catch (IllegalArgumentException ex) {
            return gap(toolName, ex.getMessage(), Map.of());
        } catch (RuntimeException ex) {
            return StudioToolCallResult.failure("FABRIC_TOOL_ERROR", ex.getMessage());
        }
    }

    /**
     * Scope helper: extracts the tenant id from top-level or nested request
     * arguments so handlers can apply tenant access policy before dispatch.
     */
    public String tenantId(StudioToolCallRequest request) {
        if (request == null) {
            return DEFAULT_TENANT_ID;
        }
        return text(request.arguments(), "tenantId", DEFAULT_TENANT_ID);
    }

    /**
     * Catalog write adapter: admits artifact drafts through the same DCP validation
     * path as the public tenant catalog route.
     */
    private StudioToolCallResult catalogAdmit(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        StudioCatalogAdmissionRequest request = typedRequest(arguments, StudioCatalogAdmissionRequest.class);
        StudioCatalogAdmissionResponse response = service.admit(tenantId, request);
        String status = "VERIFIED".equals(response.status()) ? "PASS" : "GAP";
        return result(status, toolName, Map.of(
                "tenantId", tenantId,
                "response", response,
                "diagnostics", status.equals("PASS") ? List.of() : response.results()));
    }

    /**
     * Catalog read adapter: verifies that the tenant catalog offers all required
     * capabilities requested by the current runbook phase.
     */
    private StudioToolCallResult catalogVerify(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        StudioCatalogVisualsResponse catalog = service.listCatalogVisuals(tenantId);
        List<String> requiredCapabilities = strings(value(arguments, "requiredCapabilities"));
        Map<String, List<String>> providersByCapability = providersByCapability(catalog.entries());
        List<String> missing = requiredCapabilities.stream()
                .filter(capability -> !providersByCapability.containsKey(capability))
                .toList();
        String status = missing.isEmpty() ? "PASS" : "GAP";
        return result(status, toolName, Map.of(
                "tenantId", tenantId,
                "catalogHash", catalog.catalogHash(),
                "entryCount", catalog.entries().size(),
                "requiredCapabilities", requiredCapabilities,
                "missingCapabilities", missing,
                "providersByCapability", providersByCapability,
                "catalog", catalog));
    }

    /**
     * Assembly write adapter: creates the Studio assembly that later draft-session
     * tools will use as their tenant-scoped container.
     */
    private StudioToolCallResult assemblyCreate(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        StudioCreateAssemblyRequest request = typedRequest(arguments, StudioCreateAssemblyRequest.class);
        return pass(toolName, Map.of(
                "tenantId", tenantId,
                "response", service.createAssembly(tenantId, request)));
    }

    /**
     * Needs adapter: delegates source-name and inline-source analysis to Studio's
     * DCP needs extraction service.
     */
    private StudioToolCallResult needsExtract(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        StudioNeedsExtractionRequest request = typedRequest(arguments, StudioNeedsExtractionRequest.class);
        return pass(toolName, Map.of(
                "tenantId", tenantId,
                "assemblyId", assemblyId,
                "response", service.extractNeeds(tenantId, assemblyId, request)));
    }

    /**
     * Session write adapter: opens a governed Studio draft session for later
     * intent application and compile steps.
     */
    private StudioToolCallResult sessionStart(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        StudioCreateDraftCompositionRequest request = typedRequest(arguments, StudioCreateDraftCompositionRequest.class);
        return pass(toolName, Map.of(
                "tenantId", tenantId,
                "assemblyId", assemblyId,
                "response", service.createDraftSession(tenantId, assemblyId, request)));
    }

    /**
     * Authoring adapter: invokes Fabric's existing authoring conversation facade,
     * which may delegate to Foundry over DCP when configured.
     */
    private StudioToolCallResult authoringConverse(String toolName, Map<String, Object> arguments) {
        StudioAuthoringConverseRequest request = typedRequest(arguments, StudioAuthoringConverseRequest.class);
        StudioAuthoringConverseResponse response = service.converseAuthoring(request);
        String status = "proposal".equals(response.kind()) ? "PASS" : "GAP";
        return result(status, toolName, Map.of("response", response));
    }

    /**
     * Intent write adapter: applies one Studio draft intent through the normal
     * revision and catalog-grounding checks.
     */
    private StudioToolCallResult sessionIntentApply(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        String sessionId = text(arguments, "sessionId", "");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        StudioIntentRequest request = typedRequest(arguments, StudioIntentRequest.class);
        StudioIntentResponse response = service.applyIntent(tenantId, assemblyId, sessionId, request);
        String status = "VALID".equals(response.status()) ? "PASS" : "GAP";
        return result(status, toolName, Map.of(
                "tenantId", tenantId,
                "assemblyId", assemblyId,
                "sessionId", sessionId,
                "response", response));
    }

    /**
     * Projection read adapter: returns the draft-scoped Dynamic DCP projection that
     * compile and deployment resolution are expected to agree with.
     */
    private StudioToolCallResult dynamicDcpProject(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        String sessionId = text(arguments, "sessionId", "");
        return pass(toolName, Map.of(
                "tenantId", tenantId,
                "assemblyId", assemblyId,
                "sessionId", sessionId,
                "response", service.dynamicDcpProjection(tenantId, assemblyId, sessionId)));
    }

    /**
     * Deployment adapter: resolves deployment shape from Studio session/catalog
     * state instead of asking the authoring agent for filesystem paths.
     */
    private StudioToolCallResult deploymentResolve(String toolName, Map<String, Object> arguments) {
        StudioDeploymentResolveRequest request = typedRequest(arguments, StudioDeploymentResolveRequest.class);
        StudioDeploymentResolveResponse response = service.resolveDeployment(request);
        String status = "RESOLVED".equals(response.status()) ? "PASS" : "GAP";
        return result(status, toolName, Map.of("response", response));
    }

    /**
     * Compile adapter: compiles the full draft inventory by replaying session
     * intents through Studio's candidate compiler.
     */
    private StudioToolCallResult candidateCompile(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        String sessionId = text(arguments, "sessionId", "");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        StudioCompileDraftCandidateRequest request = typedRequest(arguments, StudioCompileDraftCandidateRequest.class);
        StudioCompileDraftCandidateResponse response = service.compileCandidate(tenantId, assemblyId, sessionId, request);
        String status = "COMPILED".equals(response.status()) ? "PASS" : "GAP";
        return result(status, toolName, Map.of("response", response));
    }

    /**
     * Export read adapter: downloads one hash-pinned export artifact and returns
     * base64 content so Foundry's JSON tool result can persist it as a run artifact.
     */
    private StudioToolCallResult exportDownload(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String artifactId = text(arguments, "artifactId", "");
        String sha256 = text(arguments, "sha256", "");
        if (artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId is required");
        }
        Optional<StudioAssetContent> content = service.exportArtifactContent(tenantId, artifactId, sha256);
        if (content.isEmpty()) {
            return gap(toolName, "export artifact is unavailable or hash verification failed", Map.of(
                    "tenantId", tenantId,
                    "artifactId", artifactId,
                    "sha256", sha256));
        }
        StudioAssetContent asset = content.get();
        return pass(toolName, Map.of(
                "tenantId", tenantId,
                "artifactId", artifactId,
                "mediaType", asset.mediaType(),
                "sha256", asset.sha256(),
                "byteLength", asset.bytes().length,
                "contentBase64", Base64.getEncoder().encodeToString(asset.bytes())));
    }

    /**
     * Result builder: wraps a successful Studio operation as a PASS result.
     */
    private StudioToolCallResult pass(String toolName, Map<String, Object> payload) {
        return result("PASS", toolName, payload);
    }

    /**
     * Result builder: wraps a business/design gap as a successful tool call whose
     * runbook status tells the agent to stop.
     */
    private StudioToolCallResult gap(String toolName, String message, Map<String, Object> payload) {
        Map<String, Object> merged = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        merged.put("diagnostics", List.of(message));
        return result("GAP", toolName, merged);
    }

    /**
     * Result builder: writes the canonical runbook status and tool name into each
     * output map while preserving the delegated Studio response object.
     */
    private StudioToolCallResult result(String status, String toolName, Map<String, Object> payload) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("tool", toolName);
        if (payload != null) {
            output.putAll(payload);
        }
        return StudioToolCallResult.success(output);
    }

    /**
     * Conversion helper: reads either a nested `request` object or the full
     * arguments map as a concrete Studio DTO.
     */
    private <T> T typedRequest(Map<String, Object> arguments, Class<T> type) {
        Object nested = arguments == null ? null : arguments.get("request");
        Object source = nested instanceof Map<?, ?> ? nested : arguments;
        return mapper.convertValue(source == null ? Map.of() : source, type);
    }

    /**
     * Argument helper: returns a top-level argument value, falling back to the
     * nested `request` object for standard Studio DTO fields.
     */
    private Object value(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        if (arguments.containsKey(key)) {
            return arguments.get(key);
        }
        Object nested = arguments.get("request");
        if (nested instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    /**
     * Argument helper: extracts a trimmed string from top-level or nested request
     * fields, returning the provided default when absent.
     */
    private String text(Map<String, Object> arguments, String key, String defaultValue) {
        Object raw = value(arguments, key);
        if (raw == null) {
            return defaultValue;
        }
        String value = String.valueOf(raw).trim();
        return value.isBlank() ? defaultValue : value;
    }

    /**
     * Argument helper: projects a JSON scalar or collection into a stable list of
     * nonblank strings.
     */
    private List<String> strings(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(item -> String.valueOf(item).trim())
                    .toList();
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(raw).trim());
    }

    /**
     * Catalog projector: builds a deterministic capability-to-provider map from
     * Studio visual port metadata.
     */
    private Map<String, List<String>> providersByCapability(List<StudioVisualCatalogEntry> entries) {
        Map<String, Set<String>> providers = new TreeMap<>();
        for (StudioVisualCatalogEntry entry : entries == null ? List.<StudioVisualCatalogEntry>of() : entries) {
            for (String capability : offeredCapabilities(entry.visualManifest())) {
                providers.computeIfAbsent(capability, ignored -> new LinkedHashSet<>())
                        .add(entry.catalogEntryId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        providers.forEach((capability, catalogEntries) ->
                result.put(capability, List.copyOf(catalogEntries)));
        return result;
    }

    /**
     * Visual metadata projector: extracts DCP offer capabilities from the `ports`
     * array written by Studio catalog admission and fixture projection.
     */
    private List<String> offeredCapabilities(Map<String, Object> visualManifest) {
        Object ports = visualManifest == null ? null : visualManifest.get("ports");
        if (!(ports instanceof List<?> list)) {
            return List.of();
        }
        List<String> capabilities = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> port)) {
                continue;
            }
            if (!"OFFER".equals(String.valueOf(port.get("kind")))) {
                continue;
            }
            String mapsTo = String.valueOf(port.get("mapsTo"));
            if (mapsTo.startsWith("claim.offers.")) {
                capabilities.add(mapsTo.substring("claim.offers.".length()));
            }
        }
        return capabilities.stream().sorted().toList();
    }

    /**
     * Capability helper: lists the Studio-backed tools implemented by this gateway
     * for diagnostics and Step 7 verification output.
     */
    private List<String> supportedTools() {
        return List.of(
                "fabric.catalog-admit",
                "fabric.catalog-verify",
                "fabric.assembly-create",
                "fabric.needs-extract",
                "fabric.session-start",
                "fabric.authoring-converse",
                "fabric.session-intent-apply",
                "fabric.dynamic-dcp-project",
                "fabric.deployment-resolve",
                "fabric.candidate-compile",
                "fabric.export-download");
    }
}
