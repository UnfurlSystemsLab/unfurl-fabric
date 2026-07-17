package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;

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
            StudioToolCallResult result = switch (toolName) {
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
            return withOptionalOutputArtifact(result, request.arguments());
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
     * path as the public tenant catalog route. For Flowfoundry runbook execution
     * it can expand a Step 1 inventory into artifactBase64 drafts before calling
     * the normal service.
     */
    private StudioToolCallResult catalogAdmit(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        StudioCatalogAdmissionRequest request = typedRequest(catalogAdmitArguments(arguments),
                StudioCatalogAdmissionRequest.class);
        StudioCatalogAdmissionResponse response = service.admit(tenantId, request);
        String status = "VERIFIED".equals(response.status()) ? "PASS" : "GAP";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("response", response);
        payload.put("diagnostics", status.equals("PASS") ? List.of() : response.results());
        String outputPath = text(arguments, "outputPath", "");
        if (!outputPath.isBlank()) {
            payload.put("artifact", writeToolArtifact(outputPath, response));
        }
        return result(status, toolName, payload);
    }

    /**
     * Argument projector: converts `artifactInventoryPath` or inline `artifactInventory`
     * into the normal catalog admission request shape. This keeps binary artifact
     * content out of prompts while preserving Studio admission as the validation authority.
     */
    private Map<String, Object> catalogAdmitArguments(Map<String, Object> arguments) {
        Map<String, Object> inventory = artifactInventory(arguments);
        if (inventory.isEmpty()) {
            return arguments;
        }
        Map<String, Object> expanded = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Map<String, Object> request = asMap(value(arguments, "request"));
        if (request.isEmpty()) {
            request = new LinkedHashMap<>();
        } else {
            request = new LinkedHashMap<>(request);
        }
        request.putIfAbsent("assemblyId", text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID));
        request.put("artifacts", artifactDraftsFromInventory(inventory));
        expanded.put("request", request);
        return expanded;
    }

    /**
     * Inventory reader: accepts either an inline Step 1 inventory object or a local
     * JSON path generated by the runbook inventory step.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> artifactInventory(Map<String, Object> arguments) {
        Object inline = value(arguments, "artifactInventory");
        if (inline instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, inventoryValue) -> result.put(String.valueOf(key), inventoryValue));
            return result;
        }
        String path = text(arguments, "artifactInventoryPath", text(arguments, "inventoryPath", ""));
        if (path.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(resolveLocalPath(path).toFile(), Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("unable to read artifact inventory: " + path, ex);
        }
    }

    /**
     * Inventory mapper: turns each existing inventory artifact into a JAR upload draft
     * for the canonical Studio admission service.
     */
    private List<Map<String, Object>> artifactDraftsFromInventory(Map<String, Object> inventory) {
        Object artifacts = inventory.get("artifacts");
        if (!(artifacts instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("artifact inventory must contain a non-empty artifacts list");
        }
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> artifact = new LinkedHashMap<>();
            raw.forEach((key, value) -> artifact.put(String.valueOf(key), value));
            if (!truthy(artifact.getOrDefault("exists", true))) {
                continue;
            }
            String path = text(artifact, "path", "");
            if (path.isBlank()) {
                continue;
            }
            Path artifactPath = resolveLocalPath(path);
            try {
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("fileName", artifactPath.getFileName().toString());
                draft.put("sha256", text(artifact, "sha256", ""));
                draft.put("artifactBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(artifactPath)));
                drafts.add(draft);
            } catch (Exception ex) {
                throw new IllegalArgumentException("unable to read catalog artifact: " + path, ex);
            }
        }
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("artifact inventory did not contain any readable artifacts");
        }
        return List.copyOf(drafts);
    }

    /**
     * Filesystem resolver: supports both workspace-root and module-root relative
     * paths for local runbook execution while requiring the resolved file to exist.
     */
    private Path resolveLocalPath(String rawPath) {
        Path raw = Path.of(rawPath);
        List<Path> candidates = new ArrayList<>();
        if (raw.isAbsolute()) {
            candidates.add(raw);
        } else {
            Path cwd = Path.of("").toAbsolutePath().normalize();
            candidates.add(cwd.resolve(raw));
            Path parent = cwd.getParent();
            if (parent != null) {
                candidates.add(parent.resolve(raw));
            }
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("local artifact path does not exist: " + rawPath);
    }

    /**
     * Artifact writer: persists a tool response into the local run workspace and
     * returns hash metadata for the Flow runbook execution node.
     */
    private Map<String, Object> writeToolArtifact(String outputPath, Object response) {
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(response);
            Path path = resolveOutputPath(outputPath);
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
            return Map.of(
                    "path", outputPath.replace('\\', '/'),
                    "sha256", "sha256:" + sha256(bytes),
                    "byteLength", bytes.length);
        } catch (Exception ex) {
            throw new IllegalArgumentException("unable to write tool artifact: " + outputPath, ex);
        }
    }

    /**
     * Artifact decorator: when Flow runbook nodes pass `outputPath`, writes the
     * canonical tool output as the handoff artifact. Tool handlers that already
     * wrote a specialized artifact, such as catalog admission, are left intact.
     */
    private StudioToolCallResult withOptionalOutputArtifact(StudioToolCallResult result, Map<String, Object> arguments) {
        if (result == null || !result.success() || result.output().containsKey("artifact")) {
            return result;
        }
        String outputPath = text(arguments, "outputPath", "");
        if (outputPath.isBlank()) {
            return result;
        }
        Map<String, Object> output = new LinkedHashMap<>(result.output());
        output.put("artifact", writeToolArtifact(outputPath, result.output()));
        return StudioToolCallResult.success(output);
    }

    /**
     * Output resolver: supports module-root and workspace-root relative run artifact
     * paths, preferring an existing parent directory when one is available.
     */
    private Path resolveOutputPath(String rawPath) {
        Path raw = Path.of(rawPath);
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(cwd.resolve(raw));
        Path parent = cwd.getParent();
        if (parent != null) {
            candidates.add(parent.resolve(raw));
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> path.getParent() != null && Files.exists(path.getParent()))
                .findFirst()
                .orElse(candidates.getFirst().toAbsolutePath().normalize());
    }

    /**
     * Hash helper: computes the artifact SHA-256 using JDK crypto primitives.
     */
    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is unavailable", ex);
        }
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
     * Intent write adapter: applies one or more Studio draft intents through the
     * normal revision and catalog-grounding checks. Batch mode lets Flow pass the
     * whole authoring proposal intent list without needing list-index references.
     */
    private StudioToolCallResult sessionIntentApply(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        String sessionId = text(arguments, "sessionId", "");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        Object rawIntents = value(arguments, "intents");
        if (rawIntents instanceof Collection<?> intents) {
            return sessionIntentApplyBatch(toolName, arguments, tenantId, assemblyId, sessionId, intents);
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
     * Batch strategy: overlays tenant/session/revision defaults onto each
     * proposal intent and stops at the first Studio rejection so Flow receives a
     * single PASS/GAP artifact for Step 9.
     */
    private StudioToolCallResult sessionIntentApplyBatch(
            String toolName,
            Map<String, Object> arguments,
            String tenantId,
            String assemblyId,
            String sessionId,
            Collection<?> intents
    ) {
        if (intents.isEmpty()) {
            return gap(toolName, "intents must contain at least one intent", Map.of(
                    "tenantId", tenantId,
                    "assemblyId", assemblyId,
                    "sessionId", sessionId));
        }
        List<StudioIntentResponse> responses = new ArrayList<>();
        long nextRevision = longValue(value(arguments, "baseRevision"), 0);
        for (Object rawIntent : intents) {
            Map<String, Object> intent = new LinkedHashMap<>(asMap(rawIntent));
            intent.putIfAbsent("tenantId", tenantId);
            intent.putIfAbsent("assemblyId", assemblyId);
            intent.putIfAbsent("sessionId", sessionId);
            intent.putIfAbsent("baseRevision", nextRevision);
            putIfPresent(intent, "collaboratorId", value(arguments, "collaboratorId"));
            putIfPresent(intent, "collaboratorName", value(arguments, "collaboratorName"));
            StudioIntentResponse response = service.applyIntent(
                    tenantId,
                    assemblyId,
                    sessionId,
                    mapper.convertValue(intent, StudioIntentRequest.class));
            responses.add(response);
            if (!"VALID".equals(response.status())) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tenantId", tenantId);
                payload.put("assemblyId", assemblyId);
                payload.put("sessionId", sessionId);
                payload.put("appliedCount", responses.size() - 1);
                payload.put("responses", responses);
                payload.put("finalSession", response.session());
                return result("GAP", toolName, payload);
            }
            nextRevision = response.newRevision();
        }
        StudioIntentResponse last = responses.getLast();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("assemblyId", assemblyId);
        payload.put("sessionId", sessionId);
        payload.put("appliedCount", responses.size());
        payload.put("responses", responses);
        payload.put("finalSession", last.session());
        return result("PASS", toolName, payload);
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
     * Argument helper: projects a JSON object-like value into a mutable string-keyed map.
     */
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
        return result;
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
     * Boolean helper: treats absent inventory flags as true and accepts JSON
     * booleans or strings from persisted run artifacts.
     */
    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Numeric argument helper: accepts JSON numeric values or numeric strings
     * when Flow passes a revision through the runbook artifact graph.
     */
    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * Map helper: overlays optional top-level defaults onto per-intent payloads
     * without replacing values supplied by the authoring proposal itself.
     */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.putIfAbsent(key, value);
        }
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
