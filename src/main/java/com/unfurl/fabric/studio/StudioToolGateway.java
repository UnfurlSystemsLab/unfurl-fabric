package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.deployment.domain.DeploymentShape;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.runtime.RuntimeBindingSetGenerator;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipInputStream;

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
                case "fabric.artifact-inventory" -> artifactInventoryTool(toolName, request.arguments());
                case "fabric.catalog-admit" -> catalogAdmit(toolName, request.arguments());
                case "fabric.catalog-verify" -> catalogVerify(toolName, request.arguments());
                case "fabric.file-list" -> fileList(toolName, request.arguments());
                case "fabric.assembly-create" -> assemblyCreate(toolName, request.arguments());
                case "fabric.needs-extract" -> needsExtract(toolName, request.arguments());
                case "fabric.session-start" -> sessionStart(toolName, request.arguments());
                case "fabric.session-history" -> sessionHistory(toolName, request.arguments());
                case "fabric.authoring-converse" -> authoringConverse(toolName, request.arguments());
                case "fabric.session-intent-apply" -> sessionIntentApply(toolName, request.arguments());
                case "fabric.dynamic-dcp-project" -> dynamicDcpProject(toolName, request.arguments());
                case "fabric.deployment-resolve" -> deploymentResolve(toolName, request.arguments());
                case "fabric.candidate-compile" -> candidateCompile(toolName, request.arguments());
                case "fabric.export-download" -> exportDownload(toolName, request.arguments());
                case "fabric.export-download-all" -> exportDownloadAll(toolName, request.arguments());
                case "fabric.runtime-binding-generate" -> runtimeBindingGenerate(toolName, request.arguments());
                case "fabric.foundry-root-assemble" -> foundryRootAssemble(toolName, request.arguments());
                case "fabric.flow-root-assemble" -> flowRootAssemble(toolName, request.arguments());
                case "fabric.container-image-build" -> containerImageBuild(toolName, request.arguments());
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
     * Deployment-runner tool: inventories local artifacts, computes hashes, and
     * returns clarification gaps for missing required catalog/runtime files.
     */
    private StudioToolCallResult artifactInventoryTool(String toolName, Map<String, Object> arguments) {
        Object rawFiles = firstValue(arguments, "files", "catalogFiles", "artifacts");
        if (!(rawFiles instanceof Collection<?> files) || files.isEmpty()) {
            return gap(toolName, "artifact inventory requires a non-empty files list", Map.of(),
                    List.of("Which local artifact files should be admitted for this Flowfoundry run?"));
        }
        List<Map<String, Object>> artifacts = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();
        for (Object rawFile : files) {
            Map<String, Object> spec = artifactSpec(rawFile);
            String requested = text(spec, "path", "");
            if (requested.isBlank()) {
                continue;
            }
            boolean required = truthy(spec.getOrDefault("required", true));
            List<Path> matches = expandArtifactPaths(requested);
            if (matches.isEmpty()) {
                Map<String, Object> missing = new LinkedHashMap<>(spec);
                missing.put("path", requested);
                missing.put("exists", false);
                missing.put("required", required);
                artifacts.add(missing);
                if (required) {
                    missingRequired.add(requested);
                }
                continue;
            }
            for (Path match : matches) {
                artifacts.add(inventoryRow(match, spec, required));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifacts", artifacts);
        payload.put("artifactCount", artifacts.size());
        payload.put("missingRequired", missingRequired);
        payload.put("workspaceRoot", Path.of("").toAbsolutePath().normalize().toString());
        if (!missingRequired.isEmpty()) {
            return gap(toolName, "required catalog artifacts are missing", payload,
                    missingRequired.stream()
                            .map(path -> "Provide or correct the local path for " + path)
                            .toList());
        }
        return pass(toolName, payload);
    }

    /**
     * Deployment-runner tool: downloads every hash-pinned compile artifact into a
     * local export directory for later runtime-binding and deployment-root tools.
     */
    private StudioToolCallResult exportDownloadAll(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String outputDir = text(arguments, "outputDir", text(arguments, "exportDir", ""));
        if (outputDir.isBlank()) {
            return gap(toolName, "export-download-all requires outputDir", Map.of(),
                    List.of("Where should downloaded Flowfoundry export artifacts be stored?"));
        }
        Map<String, Object> compileResponse = compileResponse(arguments);
        boolean includeDiagnostics = value(arguments, "includeDiagnostics") != null
                && truthy(value(arguments, "includeDiagnostics"));
        List<Map<String, Object>> refs = compileArtifactRefs(compileResponse, includeDiagnostics);
        if (refs.isEmpty()) {
            return gap(toolName, "compile response does not contain downloadable artifacts", Map.of(),
                    List.of("Provide the Step 12 compile response or an explicit artifacts list."));
        }
        Path dir = resolveOutputPath(outputDir);
        List<Map<String, Object>> downloaded = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (Map<String, Object> ref : refs) {
            String artifactId = text(ref, "artifactId", "");
            String expectedSha = text(ref, "sha256", "");
            if (artifactId.isBlank()) {
                gaps.add("artifact is missing artifactId: " + ref);
                continue;
            }
            String role = text(ref, "role", "");
            Optional<StudioAssetContent> content = "diagnostic".equals(role)
                    ? service.diagnosticArtifactContent(tenantId, artifactId, expectedSha)
                    : service.exportArtifactContent(tenantId, artifactId, expectedSha);
            if (content.isEmpty()) {
                gaps.add("artifact " + artifactId + " is unavailable or failed hash verification");
                continue;
            }
            StudioAssetContent asset = content.get();
            Path target = dir.resolve(artifactFileName(ref, asset.mediaType())).normalize();
            if (!target.startsWith(dir.toAbsolutePath().normalize())) {
                gaps.add("artifact " + artifactId + " resolves outside outputDir");
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, asset.bytes());
                downloaded.add(Map.of(
                        "role", role.isBlank() ? artifactId : role,
                        "artifactId", artifactId,
                        "path", target.toString(),
                        "sha256", asset.sha256(),
                        "mediaType", asset.mediaType(),
                        "byteLength", asset.bytes().length));
            } catch (IOException ex) {
                gaps.add("unable to write " + artifactId + ": " + ex.getMessage());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("outputDir", dir.toString());
        payload.put("downloadedArtifacts", downloaded);
        payload.put("diagnostics", gaps);
        if (!gaps.isEmpty()) {
            return result("GAP", toolName, payload);
        }
        return pass(toolName, payload);
    }

    /**
     * Deployment-runner tool: generates a DCP runtime-binding tree from the signed
     * Fabric compiler support envelope and writes the YAML handoff artifact.
     */
    private StudioToolCallResult runtimeBindingGenerate(String toolName, Map<String, Object> arguments) {
        String signedPath = text(arguments, "signedCompiledContractPath",
                text(arguments, "signedContractPath", ""));
        String outputPath = text(arguments, "runtimeBindingPath",
                text(arguments, "out", text(arguments, "outputPath", "")));
        if (signedPath.isBlank() || outputPath.isBlank()) {
            return gap(toolName, "runtime-binding generation requires signedCompiledContractPath and runtimeBindingPath",
                    Map.of(),
                    List.of("Which signed compiled support envelope should be used?",
                            "Where should step-14-runtime-binding.yaml be written?"));
        }
        try {
            Path signed = resolveLocalPath(signedPath);
            byte[] signedBytes = Files.readAllBytes(signed);
            SignedFabricContract contract = new SignedFabricContractCodec().parse(signedBytes);
            RuntimeBindingSetGenerator.Options defaults = RuntimeBindingSetGenerator.Options.defaults();
            RuntimeBindingSetGenerator.Options options = new RuntimeBindingSetGenerator.Options(
                    signed,
                    "sha256:" + sha256(signedBytes),
                    contract.canonicalHash(),
                    contract.signerKeyFingerprint(),
                    text(arguments, "tenant", text(arguments, "tenantId", defaults.tenant())),
                    text(arguments, "environment", defaults.environment()),
                    text(arguments, "telemetryNamespace", defaults.telemetryNamespace()),
                    text(arguments, "bindingIdPrefix", defaults.bindingIdPrefix()),
                    text(arguments, "flowBaseUrl", defaults.flowBaseUrl()),
                    text(arguments, "foundryBaseUrl", defaults.foundryBaseUrl()),
                    (int) longValue(firstValue(arguments, "timeoutMs", "timeout-ms"), defaults.timeoutMs()));
            byte[] yaml = new RuntimeBindingSetGenerator().generate(contract, options);
            Path target = resolveOutputPath(outputPath);
            Files.createDirectories(target.getParent());
            Files.write(target, yaml);
            return pass(toolName, Map.of(
                    "runtimeBindingArtifact", artifactMetadata(target, yaml.length, sha256(yaml)),
                    "artifact", artifactMetadata(target, yaml.length, sha256(yaml)),
                    "bindingCount", 1 + contract.contract().childContracts().size(),
                    "sourceSignedContract", signed.toString(),
                    "canonicalHash", contract.canonicalHash()));
        } catch (Exception ex) {
            return gap(toolName, "unable to generate runtime binding: " + ex.getMessage(), Map.of(),
                    List.of("Confirm Step 12 signed support artifact exists and signing was enabled.",
                            "Confirm Flow and Foundry base URLs for the target environment."));
        }
    }

    /**
     * Deployment-runner tool: assembles a Foundry deployment root from a source
     * deployment directory or explicit agent/prompt/tool/registry file lists.
     */
    private StudioToolCallResult foundryRootAssemble(String toolName, Map<String, Object> arguments) {
        String outputDir = text(arguments, "outputDir", text(arguments, "foundryRootPath", ""));
        String signedContractPath = text(arguments, "signedContractPath", "");
        String runtimeBindingPath = text(arguments, "runtimeBindingPath", "");
        String signedCompiledPath = text(arguments, "signedCompiledContractPath", "");
        if (outputDir.isBlank() || signedContractPath.isBlank() || runtimeBindingPath.isBlank()
                || signedCompiledPath.isBlank()) {
            return gap(toolName, "foundry root assembly requires outputDir, signedContractPath, runtimeBindingPath, and signedCompiledContractPath",
                    Map.of(),
                    List.of("Which Foundry deployment root output directory should be written?",
                            "Which signed root contract, signed compiled support envelope, and runtime binding should be mounted?"));
        }
        try {
            Path root = resolveOutputPath(outputDir);
            Files.createDirectories(root);
            copyFoundryDeploymentInputs(arguments, root);
            copyFile(resolveLocalPath(signedContractPath), root.resolve("signed-contract.yaml"));
            copyFile(resolveLocalPath(runtimeBindingPath), root.resolve("runtime-binding.yaml"));
            copyFile(resolveLocalPath(signedCompiledPath), root.resolve("signed-compiled-contract.yaml"));
            List<String> gaps = foundryRootGaps(root);
            Map<String, Object> payload = Map.of(
                    "rootPath", root.toString(),
                    "inventory", inventoryTree(root),
                    "treeSha256", treeSha256(root),
                    "diagnostics", gaps);
            if (!gaps.isEmpty()) {
                return result("GAP", toolName, payload);
            }
            return pass(toolName, payload);
        } catch (Exception ex) {
            return gap(toolName, "unable to assemble Foundry deployment root: " + ex.getMessage(), Map.of(),
                    List.of("Provide a Foundry source root or explicit agent/prompt/tool/registry file lists."));
        }
    }

    /**
     * Deployment-runner tool: assembles Flow's deployment root from the signed
     * handoff, runtime binding, workflow definitions, DCP runtime bundle, and trust keys.
     */
    private StudioToolCallResult flowRootAssemble(String toolName, Map<String, Object> arguments) {
        String outputDir = text(arguments, "outputDir", text(arguments, "flowRootPath", ""));
        List<String> required = List.of("signedContractPath", "signedCompiledContractPath", "runtimeBindingPath",
                "substrateProfilePath", "dcpRuntimeBundlePath", "flowWorkflowsPath", "trustKeysPath");
        List<String> missing = required.stream()
                .filter(key -> text(arguments, key, "").isBlank())
                .toList();
        if (outputDir.isBlank() || !missing.isEmpty()) {
            return gap(toolName, "flow root assembly is missing required handoff inputs", Map.of("missing", missing),
                    List.of("Provide Flow root outputDir and all signed contract/runtime-binding/workflow/trust-key paths.",
                            "If no workload workflow exists yet, provide the Flowfoundry runbook workflow directory."));
        }
        try {
            Path root = resolveOutputPath(outputDir);
            Files.createDirectories(root);
            copyFile(resolveLocalPath(text(arguments, "signedContractPath", "")), root.resolve("signed-contract.yaml"));
            copyFile(resolveLocalPath(text(arguments, "signedCompiledContractPath", "")), root.resolve("signed-compiled-contract.yaml"));
            copyFile(resolveLocalPath(text(arguments, "runtimeBindingPath", "")), root.resolve("runtime-binding.yaml"));
            copyFile(resolveLocalPath(text(arguments, "substrateProfilePath", "")), root.resolve("substrate-profile.yaml"));
            copyWorkflows(resolveExistingPath(text(arguments, "flowWorkflowsPath", "")), root.resolve("workflows"));
            extractZipSafely(resolveLocalPath(text(arguments, "dcpRuntimeBundlePath", "")), root);
            copyTrustKeys(resolveExistingPath(text(arguments, "trustKeysPath", "")), root.resolve("trust-keys"));
            Files.createDirectories(root.resolve("profiles"));
            Files.writeString(root.resolve("profiles/flow-runtime-profile.yaml"), flowRuntimeProfile(arguments),
                    StandardCharsets.UTF_8);
            List<String> gaps = flowRootGaps(root);
            Map<String, Object> payload = Map.of(
                    "rootPath", root.toString(),
                    "inventory", inventoryTree(root),
                    "treeSha256", treeSha256(root),
                    "diagnostics", gaps);
            if (!gaps.isEmpty()) {
                return result("GAP", toolName, payload);
            }
            return pass(toolName, payload);
        } catch (Exception ex) {
            return gap(toolName, "unable to assemble Flow deployment root: " + ex.getMessage(), Map.of(),
                    List.of("Confirm the DCP runtime bundle is a safe zip and the trust key directory contains PEM files."));
        }
    }

    /**
     * Deployment-runner tool: validates deployment roots and either emits an image
     * build plan or executes explicit Docker build requests chosen by the operator.
     */
    private StudioToolCallResult containerImageBuild(String toolName, Map<String, Object> arguments) {
        String mode = text(arguments, "buildMode", text(arguments, "mode", ""));
        String flowRoot = text(arguments, "flowRootPath", "");
        String foundryRoot = text(arguments, "foundryRootPath", "");
        if (flowRoot.isBlank() || foundryRoot.isBlank() || mode.isBlank()) {
            return gap(toolName, "container image build requires flowRootPath, foundryRootPath, and buildMode",
                    Map.of("supportedBuildModes", List.of("PLAN_ONLY", "DOCKER_BUILD")),
                    List.of("Should Step 17 run Docker builds now or emit a build plan only?",
                            "Which Flow and Foundry deployment roots should be validated?"));
        }
        Path flow = resolveExistingPath(flowRoot);
        Path foundry = resolveExistingPath(foundryRoot);
        if (!Files.isDirectory(flow) || !Files.isDirectory(foundry)) {
            return gap(toolName, "flowRootPath and foundryRootPath must both be directories", Map.of(),
                    List.of("Provide the Step 15 and Step 16 deployment-root directories."));
        }
        if ("PLAN_ONLY".equalsIgnoreCase(mode) || "VALIDATE_ONLY".equalsIgnoreCase(mode)) {
            return pass(toolName, Map.of(
                    "buildMode", mode.toUpperCase(),
                    "built", false,
                    "flowRootSha256", treeSha256(flow),
                    "foundryRootSha256", treeSha256(foundry),
                    "imagePlan", imagePlan(arguments)));
        }
        if (!"DOCKER_BUILD".equalsIgnoreCase(mode)) {
            return gap(toolName, "unsupported buildMode: " + mode, Map.of("supportedBuildModes", List.of("PLAN_ONLY", "DOCKER_BUILD")),
                    List.of("Choose PLAN_ONLY or DOCKER_BUILD for Step 17."));
        }
        List<Map<String, Object>> images = imageBuildSpecs(arguments);
        if (images.isEmpty()) {
            return gap(toolName, "DOCKER_BUILD mode requires an images list with imageTag and contextPath", Map.of(),
                    List.of("Which Docker build contexts and image tags should be built for Flow and Foundry?"));
        }
        return dockerBuild(toolName, images, flow, foundry);
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
        Path normalized = resolveExistingPath(rawPath);
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("local artifact path is not a file: " + rawPath);
        }
        return normalized;
    }

    /**
     * Filesystem resolver: supports absolute, module-relative, and workspace-root
     * relative paths for local deployment-runner tool inputs.
     */
    private Path resolveExistingPath(String rawPath) {
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
            if (Files.exists(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("local artifact path does not exist: " + rawPath);
    }

    /**
     * Inventory helper: normalizes one string or object file spec into a mutable map.
     */
    private Map<String, Object> artifactSpec(Object rawFile) {
        if (rawFile instanceof Map<?, ?> map) {
            return stringObjectMap(map);
        }
        return new LinkedHashMap<>(Map.of("path", String.valueOf(rawFile)));
    }

    /**
     * Inventory helper: expands direct file paths or simple local glob expressions.
     */
    private List<Path> expandArtifactPaths(String rawPath) {
        if (!containsGlob(rawPath)) {
            try {
                Path path = resolveLocalPath(rawPath);
                return List.of(path);
            } catch (IllegalArgumentException ex) {
                return List.of();
            }
        }
        Path base = globBase(rawPath);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        String normalizedPattern = normalizeGlob(rawPath);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);
        try (var paths = Files.walk(base)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(Path.of(path.toString().replace('\\', '/')))
                            || matcher.matches(Path.of(base.relativize(path).toString().replace('\\', '/'))))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to expand artifact glob: " + rawPath, ex);
        }
    }

    /**
     * Inventory helper: creates one hash-pinned row for an existing local file.
     */
    private Map<String, Object> inventoryRow(Path path, Map<String, Object> spec, boolean required) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            Map<String, Object> row = new LinkedHashMap<>(spec);
            row.put("path", path.toString());
            row.put("fileName", path.getFileName().toString());
            row.put("exists", true);
            row.put("required", required);
            row.put("sha256", "sha256:" + sha256(bytes));
            row.put("byteLength", bytes.length);
            return row;
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to hash artifact: " + path, ex);
        }
    }

    /** Glob helper: detects local glob metacharacters. */
    private boolean containsGlob(String rawPath) {
        return rawPath != null && (rawPath.contains("*") || rawPath.contains("?") || rawPath.contains("["));
    }

    /**
     * Glob helper: chooses a safe walk root before the first wildcard segment.
     */
    private Path globBase(String rawPath) {
        String normalized = rawPath.replace('\\', '/');
        int wildcard = Math.min(indexOrEnd(normalized, '*'), Math.min(indexOrEnd(normalized, '?'), indexOrEnd(normalized, '[')));
        int slash = normalized.substring(0, wildcard).lastIndexOf('/');
        String prefix = slash <= 0 ? "." : normalized.substring(0, slash);
        Path raw = Path.of(prefix);
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path candidate = cwd.resolve(raw).normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path parent = cwd.getParent();
        return parent == null ? candidate : parent.resolve(raw).normalize();
    }

    /** Glob helper: normalizes a local glob for JDK matching. */
    private String normalizeGlob(String rawPath) {
        Path raw = Path.of(rawPath);
        if (raw.isAbsolute()) {
            return rawPath.replace('\\', '/');
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return cwd.resolve(raw).normalize().toString().replace('\\', '/');
    }

    /** Text helper: returns a string index or the text length when absent. */
    private int indexOrEnd(String value, char token) {
        int index = value.indexOf(token);
        return index < 0 ? value.length() : index;
    }

    /**
     * Compile artifact collector: reads Step 12 output from inline JSON or a local artifact path.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> compileResponse(Map<String, Object> arguments) {
        Object inline = firstValue(arguments, "compileResponse", "response");
        if (inline != null) {
            Map<String, Object> result = inline instanceof Map<?, ?> map
                    ? stringObjectMap(map)
                    : mapper.convertValue(inline, Map.class);
            Object nested = result.get("response");
            if (nested instanceof Map<?, ?> nestedMap) {
                return stringObjectMap(nestedMap);
            }
            return nested == null ? result : mapper.convertValue(nested, Map.class);
        }
        String path = text(arguments, "compileResponsePath", "");
        if (!path.isBlank()) {
            try {
                Map<String, Object> result = mapper.readValue(resolveLocalPath(path).toFile(), Map.class);
                Object nested = result.get("response");
                return nested instanceof Map<?, ?> nestedMap ? stringObjectMap(nestedMap) : result;
            } catch (IOException ex) {
                throw new IllegalArgumentException("unable to read compile response: " + path, ex);
            }
        }
        Object artifacts = value(arguments, "artifacts");
        if (artifacts instanceof Collection<?>) {
            return Map.of("artifacts", artifacts);
        }
        return Map.of();
    }

    /**
     * Compile artifact collector: flattens primary and support handoff artifacts,
     * optionally including diagnostics when the caller explicitly asks for debug
     * sidecars.
     */
    private List<Map<String, Object>> compileArtifactRefs(
            Map<String, Object> compileResponse,
            boolean includeDiagnostics
    ) {
        List<Map<String, Object>> refs = new ArrayList<>();
        addArtifactRef(refs, compileResponse.get("contractArtifact"), "contract");
        addArtifactRef(refs, compileResponse.get("substrateProfileArtifact"), "substrate-profile");
        addArtifactRef(refs, compileResponse.get("signedContractArtifact"), "signed-contract");
        addArtifactRefs(refs, compileResponse.get("supportArtifacts"), "support");
        if (includeDiagnostics) {
            addArtifactRefs(refs, compileResponse.get("diagnosticArtifacts"), "diagnostic");
        }
        addArtifactRefs(refs, compileResponse.get("artifacts"), "artifact");
        return refs;
    }

    /** Compile artifact collector: adds one optional artifact reference. */
    private void addArtifactRef(List<Map<String, Object>> refs, Object raw, String role) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> ref = stringObjectMap(map);
        if (text(ref, "artifactId", "").isBlank()) {
            return;
        }
        ref.putIfAbsent("role", role);
        refs.add(ref);
    }

    /** Compile artifact collector: adds a list of artifact references. */
    private void addArtifactRefs(List<Map<String, Object>> refs, Object raw, String role) {
        if (!(raw instanceof Collection<?> collection)) {
            return;
        }
        for (Object item : collection) {
            addArtifactRef(refs, item, role);
        }
    }

    /**
     * Export artifact naming policy: maps known compile artifact ids to stable file names.
     */
    private String artifactFileName(Map<String, Object> ref, String mediaType) {
        String explicit = text(ref, "fileName", "");
        if (!explicit.isBlank()) {
            return safeFileName(explicit);
        }
        String artifactId = text(ref, "artifactId", "artifact");
        String marker = artifactId.toLowerCase(java.util.Locale.ROOT);
        if (marker.contains("signed-compiled-contract")) {
            return "signed-compiled-contract.yaml";
        }
        if (marker.contains("dcp-runtime-bundle")) {
            return "dcp-runtime-bundle.zip";
        }
        String role = text(ref, "role", artifactId);
        if ("contract".equals(role)) {
            return "contract.yaml";
        }
        if ("substrate-profile".equals(role)) {
            return "substrate-profile.yaml";
        }
        if ("signed-contract".equals(role)) {
            return "signed-contract.yaml";
        }
        return safeFileName(artifactId + extensionFor(mediaType));
    }

    /** Export artifact naming helper: maps media types to conservative suffixes. */
    private String extensionFor(String mediaType) {
        String text = mediaType == null ? "" : mediaType.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("zip")) {
            return ".zip";
        }
        if (text.contains("yaml") || text.contains("yml")) {
            return ".yaml";
        }
        if (text.contains("json")) {
            return ".json";
        }
        return ".bin";
    }

    /** Filesystem helper: prevents path separators in generated file names. */
    private String safeFileName(String value) {
        String name = value == null ? "artifact" : value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * Filesystem helper: returns hash-pinned metadata for a written artifact.
     */
    private Map<String, Object> artifactMetadata(Path path, long byteLength, String sha256Hex) {
        return Map.of(
                "path", path.toString(),
                "sha256", "sha256:" + sha256Hex,
                "byteLength", byteLength);
    }

    /**
     * Foundry root helper: copies an existing deployment root plus explicit category lists.
     */
    private void copyFoundryDeploymentInputs(Map<String, Object> arguments, Path root) throws IOException {
        String sourceRoot = text(arguments, "foundrySourceRoot", text(arguments, "sourceRoot", ""));
        if (!sourceRoot.isBlank()) {
            Path source = resolveExistingPath(sourceRoot);
            if (!Files.isDirectory(source)) {
                throw new IllegalArgumentException("foundrySourceRoot must be a directory");
            }
            for (String subdir : List.of("agents", "prompts", "tools", "providers", "registries")) {
                copyDirectoryContents(source.resolve(subdir), root.resolve(subdir));
            }
        }
        copyFiles(firstValue(arguments, "agentPaths", "agents"), root.resolve("agents"));
        copyFiles(firstValue(arguments, "promptPaths", "prompts"), root.resolve("prompts"));
        copyFiles(firstValue(arguments, "toolJarPaths", "tools"), root.resolve("tools"));
        copyFiles(firstValue(arguments, "providerPaths", "providers"), root.resolve("providers"));
        copyFiles(firstValue(arguments, "registryPaths", "registries"), root.resolve("registries"));
    }

    /** Filesystem helper: copies a list of files into a target directory. */
    private void copyFiles(Object raw, Path targetDir) throws IOException {
        if (!(raw instanceof Collection<?> files)) {
            return;
        }
        for (Object file : files) {
            Path source = resolveLocalPath(String.valueOf(file));
            copyFile(source, targetDir.resolve(source.getFileName().toString()));
        }
    }

    /** Filesystem helper: copies regular files from one directory into another. */
    private void copyDirectoryContents(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        Files.createDirectories(targetDir);
        try (var files = Files.list(sourceDir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                copyFile(file, targetDir.resolve(file.getFileName().toString()));
            }
        }
    }

    /** Filesystem helper: copies one file, creating the target parent. */
    private void copyFile(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("expected file: " + source);
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Foundry root validator: returns missing required deployment-root facts. */
    private List<String> foundryRootGaps(Path root) throws IOException {
        List<String> gaps = new ArrayList<>();
        requireAnyFile(root.resolve("agents"), ".agent.yaml", gaps, "Foundry deployment root has no agent YAML");
        requireAnyFile(root.resolve("prompts"), ".md", gaps, "Foundry deployment root has no prompt Markdown");
        requireAnyFile(root.resolve("registries"), ".yaml", gaps, "Foundry deployment root has no registry YAML");
        requireFile(root.resolve("signed-contract.yaml"), gaps, "Foundry deployment root missing signed-contract.yaml");
        requireFile(root.resolve("runtime-binding.yaml"), gaps, "Foundry deployment root missing runtime-binding.yaml");
        requireFile(root.resolve("signed-compiled-contract.yaml"), gaps, "Foundry deployment root missing signed-compiled-contract.yaml");
        return gaps;
    }

    /** Flow root validator: returns missing required deployment-root facts. */
    private List<String> flowRootGaps(Path root) throws IOException {
        List<String> gaps = new ArrayList<>();
        requireAnyFile(root.resolve("workflows"), ".yaml", gaps, "Flow deployment root has no workflow YAML");
        requireAnyFile(root.resolve("trust-keys"), ".pem", gaps, "Flow deployment root has no public trust key PEM");
        requireFile(root.resolve("signed-contract.yaml"), gaps, "Flow deployment root missing signed-contract.yaml");
        requireFile(root.resolve("signed-compiled-contract.yaml"), gaps, "Flow deployment root missing signed-compiled-contract.yaml");
        requireFile(root.resolve("runtime-binding.yaml"), gaps, "Flow deployment root missing runtime-binding.yaml");
        requireFile(root.resolve("substrate-profile.yaml"), gaps, "Flow deployment root missing substrate-profile.yaml");
        requireFile(root.resolve("profiles/flow-runtime-profile.yaml"), gaps, "Flow deployment root missing runtime profile");
        return gaps;
    }

    /** Validation helper: records a missing required file. */
    private void requireFile(Path path, List<String> gaps, String message) {
        if (!Files.isRegularFile(path)) {
            gaps.add(message);
        }
    }

    /** Validation helper: records when a directory lacks a required suffix. */
    private void requireAnyFile(Path dir, String suffix, List<String> gaps, String message) throws IOException {
        if (!Files.isDirectory(dir)) {
            gaps.add(message);
            return;
        }
        try (var files = Files.list(dir)) {
            boolean present = files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(suffix));
            if (!present) {
                gaps.add(message);
            }
        }
    }

    /** Flow root helper: copies workflow files from a file or directory. */
    private void copyWorkflows(Path source, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        if (Files.isRegularFile(source)) {
            copyFile(source, targetDir.resolve(source.getFileName().toString()));
            return;
        }
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("flowWorkflowsPath must be a file or directory");
        }
        try (var files = Files.list(source)) {
            List<Path> workflows = files.filter(Files::isRegularFile)
                    .filter(this::isWorkflowFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            if (workflows.isEmpty()) {
                throw new IllegalArgumentException("flowWorkflowsPath contains no workflow YAML/JSON files");
            }
            for (Path workflow : workflows) {
                copyFile(workflow, targetDir.resolve(workflow.getFileName().toString()));
            }
        }
    }

    /** Flow root helper: copies public DCP trust keys. */
    private void copyTrustKeys(Path source, Path targetDir) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("trustKeysPath must be a directory");
        }
        Files.createDirectories(targetDir);
        try (var files = Files.list(source)) {
            List<Path> keys = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pem"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("trustKeysPath contains no PEM files");
            }
            for (Path key : keys) {
                copyFile(key, targetDir.resolve(key.getFileName().toString()));
            }
        }
    }

    /** Flow root helper: safely extracts the DCP runtime bundle into the Flow root. */
    private void extractZipSafely(Path zipPath, Path root) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path entryPath = Path.of(entry.getName());
                if (entryPath.isAbsolute()) {
                    throw new IllegalArgumentException("zip entry is absolute: " + entry.getName());
                }
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("zip entry escapes deployment root: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Flow root helper: emits a minimal strict Flow runtime profile for the deployment root. */
    private String flowRuntimeProfile(Map<String, Object> arguments) {
        BindingPlan plan = new BindingPlan(List.of());
        String signedPath = text(arguments, "signedCompiledContractPath", "");
        if (!signedPath.isBlank()) {
            try {
                SignedFabricContract signed = new SignedFabricContractCodec()
                        .parse(Files.readAllBytes(resolveLocalPath(signedPath)));
                plan = signed.contract().bindingPlan();
            } catch (Exception ignored) {
                plan = new BindingPlan(List.of());
            }
        }
        Set<String> components = new java.util.TreeSet<>();
        for (BindingPlanEntry entry : plan.entries()) {
            if (entry.deploymentShape() == DeploymentShape.IN_PROCESS_LIBRARY
                    || entry.deploymentShape() == DeploymentShape.MODULAR_MONOLITH_MODULE) {
                components.add(flowComponent(entry.capability()));
            }
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: flowfoundry-local\n");
        yaml.append("engine: EMBEDDED\n");
        yaml.append("stateStore: IN_MEMORY\n");
        yaml.append("eventSink: IN_MEMORY\n");
        yaml.append("enabledComponents:\n");
        if (components.isEmpty()) {
            yaml.append("  []\n");
        } else {
            components.forEach(component -> yaml.append("  - ").append(component).append('\n'));
        }
        yaml.append("componentConfig: {}\n");
        yaml.append("triggerConfig: {}\n");
        yaml.append("substrateProfileMode: STRICT\n");
        yaml.append("fabricContractPath: \"./signed-contract.yaml\"\n");
        yaml.append("substrateProfilePath: \"./substrate-profile.yaml\"\n");
        return yaml.toString();
    }

    /** Flow profile helper: maps DCP capabilities to built-in Flow component ids. */
    private String flowComponent(String capability) {
        if (capability == null) {
            return "function.local";
        }
        if (capability.startsWith("storage.")) {
            return "storage";
        }
        if (capability.startsWith("http.")) {
            return "http.request";
        }
        if (capability.startsWith("connector.")) {
            return "connector.request";
        }
        if (capability.startsWith("human.") || capability.startsWith("approval.")) {
            return "human.approval";
        }
        if (capability.startsWith("subgraph.")) {
            return "subgraph.execute";
        }
        if (capability.startsWith("tool.result.")) {
            return "tool.result.gate";
        }
        return "function.local";
    }

    /** Workflow helper: accepts YAML, YML, and JSON workflow definitions. */
    private boolean isWorkflowFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    /** Inventory helper: returns deterministic file metadata for a directory tree. */
    private List<Map<String, Object>> inventoryTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Path file : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                byte[] bytes = Files.readAllBytes(file);
                rows.add(Map.of(
                        "path", root.relativize(file).toString().replace('\\', '/'),
                        "sha256", "sha256:" + sha256(bytes),
                        "byteLength", bytes.length));
            }
            return rows;
        }
    }

    /** Hash helper: computes a deterministic tree hash from relative file hashes. */
    private String treeSha256(Path root) {
        try {
            StringBuilder content = new StringBuilder();
            for (Map<String, Object> row : inventoryTree(root)) {
                content.append(row.get("path")).append('|').append(row.get("sha256")).append('\n');
            }
            return "sha256:" + sha256(content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to hash deployment root: " + root, ex);
        }
    }

    /** Container helper: returns a plan-only image build description. */
    private List<Map<String, Object>> imagePlan(Map<String, Object> arguments) {
        List<Map<String, Object>> images = imageBuildSpecs(arguments);
        if (!images.isEmpty()) {
            return images;
        }
        return List.of(
                Map.of("imageTag", text(arguments, "flowImageTag", "unfurl-flow:local"),
                        "root", text(arguments, "flowRootPath", "")),
                Map.of("imageTag", text(arguments, "foundryImageTag", "unfurl-foundry:local"),
                        "root", text(arguments, "foundryRootPath", "")));
    }

    /** Container helper: normalizes explicit Docker image build specs. */
    private List<Map<String, Object>> imageBuildSpecs(Map<String, Object> arguments) {
        Object raw = value(arguments, "images");
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        List<Map<String, Object>> specs = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map) {
                specs.add(stringObjectMap(map));
            }
        }
        return List.copyOf(specs);
    }

    /** Container helper: executes Docker build only after explicit operator selection. */
    private StudioToolCallResult dockerBuild(String toolName, List<Map<String, Object>> images, Path flow, Path foundry) {
        List<Map<String, Object>> builds = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (Map<String, Object> image : images) {
            String tag = text(image, "imageTag", "");
            String contextPath = text(image, "contextPath", "");
            String dockerfilePath = text(image, "dockerfilePath", "");
            if (tag.isBlank() || contextPath.isBlank()) {
                gaps.add("image build spec requires imageTag and contextPath: " + image);
                continue;
            }
            Path context = resolveExistingPath(contextPath);
            if (!Files.isDirectory(context)) {
                gaps.add("Docker context is not a directory: " + context);
                continue;
            }
            List<String> command = new ArrayList<>(List.of("docker", "build", "-t", tag));
            if (!dockerfilePath.isBlank()) {
                command.add("-f");
                command.add(resolveLocalPath(dockerfilePath).toString());
            }
            command.add(context.toString());
            try {
                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                builds.add(Map.of(
                        "imageTag", tag,
                        "exitCode", exit,
                        "logTail", tail(output, 4000)));
                if (exit != 0) {
                    gaps.add("docker build failed for " + tag);
                }
            } catch (IOException ex) {
                gaps.add("unable to start docker build for " + tag + ": " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                gaps.add("docker build interrupted for " + tag);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("buildMode", "DOCKER_BUILD");
        payload.put("built", gaps.isEmpty());
        payload.put("builds", builds);
        payload.put("flowRootSha256", treeSha256(flow));
        payload.put("foundryRootSha256", treeSha256(foundry));
        payload.put("diagnostics", gaps);
        return gaps.isEmpty() ? pass(toolName, payload) : result("GAP", toolName, payload);
    }

    /** Text helper: returns the last characters of command output for safe diagnostics. */
    private String tail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(value.length() - maxChars);
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
     * File-registry read adapter: lists tenant-isolated file rows for Flow and
     * Foundry tools so agents can select catalog/export versions without direct
     * filesystem access.
     */
    private StudioToolCallResult fileList(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String fileType = text(arguments, "fileType", "");
        String sessionId = text(arguments, "sessionId", "");
        String correlationId = text(arguments, "correlationId", "");
        List<StudioFileRecord> files = service.listFiles(tenantId, fileType, sessionId, correlationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("fileType", fileType);
        payload.put("sessionId", sessionId);
        payload.put("correlationId", correlationId);
        payload.put("fileCount", files.size());
        payload.put("files", files);
        if (files.isEmpty() && "CATALOG".equalsIgnoreCase(fileType)) {
            return gap(toolName, "no tenant catalog files are registered", payload,
                    List.of("Which catalog artifact should be admitted before starting this draft?"));
        }
        return pass(toolName, payload);
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
        if ((request.catalogFileId() == null || request.catalogFileId().isBlank())
                && (request.baseCatalogHash() == null || request.baseCatalogHash().isBlank())) {
            List<StudioFileRecord> catalogFiles = service.listFiles(tenantId, "CATALOG", "", "");
            if (catalogFiles.isEmpty()) {
                return gap(toolName, "session-start requires an admitted tenant catalog", Map.of(
                        "tenantId", tenantId,
                        "assemblyId", assemblyId),
                        List.of("Which catalog file should Fabric admit before the draft starts?"));
            }
            StudioFileRecord latestCatalog = catalogFiles.getFirst();
            request = new StudioCreateDraftCompositionRequest(
                    request.tenantId(),
                    request.assemblyId(),
                    request.baseCatalogHash(),
                    request.needsId(),
                    request.trustPolicyId(),
                    request.initialCandidateId(),
                    request.collaboratorId(),
                    request.collaboratorName(),
                    latestCatalog.fileId(),
                    request.displayName());
        }
        StudioCreateDraftCompositionResponse response = service.createDraftSession(tenantId, assemblyId, request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("assemblyId", assemblyId);
        payload.put("response", response);
        selectedCatalogFile(tenantId, response.session().catalogFileId())
                .ifPresent(file -> payload.put("catalogFile", file));
        return pass(toolName, payload);
    }

    /**
     * Session-history read adapter: exposes tenant-scoped draft sessions for
     * agents that need to continue, fork, or name a new workspace.
     */
    private StudioToolCallResult sessionHistory(String toolName, Map<String, Object> arguments) {
        String tenantId = text(arguments, "tenantId", DEFAULT_TENANT_ID);
        String assemblyId = text(arguments, "assemblyId", DEFAULT_ASSEMBLY_ID);
        List<StudioSessionHistoryItem> sessions = service.listSessionHistory(tenantId, assemblyId);
        return pass(toolName, Map.of(
                "tenantId", tenantId,
                "assemblyId", assemblyId,
                "sessionCount", sessions.size(),
                "sessions", sessions));
    }

    /**
     * Registry lookup helper: finds a catalog file row by id for enriched tool
     * output while preserving tenant isolation in the service layer.
     */
    private Optional<StudioFileRecord> selectedCatalogFile(String tenantId, String catalogFileId) {
        if (catalogFileId == null || catalogFileId.isBlank()) {
            return Optional.empty();
        }
        return service.listFiles(tenantId, "CATALOG", "", "").stream()
                .filter(file -> catalogFileId.equals(file.fileId()))
                .findFirst();
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
     * Result builder: wraps a gap with the specific clarification questions that
     * the runbook should ask before retrying the current step.
     */
    private StudioToolCallResult gap(
            String toolName,
            String message,
            Map<String, Object> payload,
            List<String> questions
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        merged.put("diagnostics", List.of(message));
        merged.put("questions", questions == null ? List.of() : List.copyOf(questions));
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
     * Argument helper: returns the first present value among several top-level or
     * nested request keys.
     */
    private Object firstValue(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            Object found = value(arguments, key);
            if (found != null) {
                return found;
            }
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
        return stringObjectMap(map);
    }

    /**
     * Argument helper: projects a map-like JSON value into a mutable string-keyed
     * map while preserving nested values for later DTO conversion.
     */
    private Map<String, Object> stringObjectMap(Map<?, ?> map) {
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
                "fabric.artifact-inventory",
                "fabric.catalog-admit",
                "fabric.catalog-verify",
                "fabric.file-list",
                "fabric.assembly-create",
                "fabric.needs-extract",
                "fabric.session-start",
                "fabric.session-history",
                "fabric.authoring-converse",
                "fabric.session-intent-apply",
                "fabric.dynamic-dcp-project",
                "fabric.deployment-resolve",
                "fabric.candidate-compile",
                "fabric.export-download",
                "fabric.export-download-all",
                "fabric.runtime-binding-generate",
                "fabric.foundry-root-assemble",
                "fabric.flow-root-assemble",
                "fabric.container-image-build");
    }
}
