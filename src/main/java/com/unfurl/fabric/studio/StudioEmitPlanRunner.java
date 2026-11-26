package com.unfurl.fabric.studio;

import com.unfurl.dcp.runtimebinding.RuntimeBinding;
import com.unfurl.dcp.runtimebinding.RuntimeBindingCodec;
import com.unfurl.dcp.runtimebinding.RuntimeBindingSet;
import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.deploy.core.EmitPipeline;
import com.unfurl.deploy.core.EmitPreflightException;
import com.unfurl.deploy.core.EmitterBootstrap;
import com.unfurl.deploy.core.EmitterBootstrapException;
import com.unfurl.deploy.spi.DeployEmitter;
import com.unfurl.deploy.spi.EmitRequest;
import com.unfurl.deploy.spi.EmitResult;
import com.unfurl.deploy.spi.EmitterConfig;
import com.unfurl.deployment.policy.DeploymentTarget;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.fabric.catalog.SelectedCatalogEntries;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.deploy.EmitTargetInputs;
import com.unfurl.fabric.runtime.HostedComponentConfiguration;
import com.unfurl.fabric.runtime.RuntimeBindingInputs;
import com.unfurl.fabric.runtime.RuntimeBindingSetGenerator;
import com.unfurl.fabric.signing.FabricContractSigner;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.substrate.api.SubstrateProfile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs one emit-plan preview (LLD-fabric-studio-api, <i>StudioEmitPlanPreview</i>): the deploy emitter's real
 * {@link EmitPipeline#emit}, never a second "plan" path, so the preview refuses exactly what a deploy refuses.
 *
 * <p>Pattern: <b>Template Method</b>. {@link #run} fixes the steps, in order: write the record's signed envelope and
 * profile; write the DCP runtime bundle ({@code DcpRuntimeBundleWriter}, as compile writes it); generate the runtime
 * bindings ({@link RuntimeBindingSetGenerator}, with the environment's inputs read by {@link RuntimeBindingInputs}); lay
 * out the hosted components' jars; thread every path into the environment's target ({@link EmitTargetInputs}, shared with
 * {@code fabric deploy}); emit. Each step's refusal becomes the preview's absent reason under the code its message starts
 * with ({@code CODE: message}), or the step's own code when it names none.
 *
 * <p>Isolation: each run gets a fresh, uniquely named temporary folder, owner-only where the file system supports POSIX
 * permissions (elsewhere it is created in the current user's temporary directory, which only that user can read). It is
 * deleted in a {@code finally} whatever the outcome, and nothing in it is returned except the facts in {@link Outcome}.
 */
final class StudioEmitPlanRunner {
    /** The step code of a bundle failure whose message names no code. */
    static final String BUNDLE_FAILED = "DCP_RUNTIME_BUNDLE_FAILED";
    /** The step code of a binding failure whose message names no code. */
    static final String BINDING_FAILED = "RUNTIME_BINDING_FAILED";
    /** The step code of an emit failure whose message names no code. */
    static final String EMIT_FAILED = "EMIT_FAILED";
    /** A signature preflight refusal. */
    static final String EMIT_PREFLIGHT_FAILED = "EMIT_PREFLIGHT_FAILED";
    /** A target kind no installed emitter renders. */
    static final String EMIT_TARGET_UNSUPPORTED = "EMIT_TARGET_UNSUPPORTED";
    /** Shapes the target cannot emit. */
    static final String EMIT_SHAPE_UNSUPPORTED = "EMIT_SHAPE_UNSUPPORTED";
    /** Hosted configuration refused by its rule; the reason is appended. */
    static final String HOSTED_CONFIGURATION = "HOSTED_CONFIGURATION_";

    private static final Pattern CODED = Pattern.compile("^([A-Z][A-Z0-9_]{2,}):\\s*(.*)$", Pattern.DOTALL);
    /** The path the bindings name as their source signed contract: the envelope's name in the emitted plan. */
    private static final Path SOURCE_SIGNED_CONTRACT = Path.of("signed-compiled-contract.yaml");

    private final Supplier<RuntimeBindingSetGenerator> generator;
    private final Supplier<EmitterBootstrap> bootstrap;

    /** Production runner: the system clock's generator and the installed emitters. */
    StudioEmitPlanRunner() {
        this(RuntimeBindingSetGenerator::new, EmitterBootstrap::new);
    }

    /**
     * @param generator the binding generator to use per run.
     * @param bootstrap the emitter discovery to use per run.
     */
    StudioEmitPlanRunner(Supplier<RuntimeBindingSetGenerator> generator, Supplier<EmitterBootstrap> bootstrap) {
        this.generator = generator;
        this.bootstrap = bootstrap;
    }

    /**
     * What a run reads, all of it checked by the caller: the record's artifacts proved to belong to the record, the
     * selections resolved in the admitted catalog, and every pinned hosted jar present in the tenant's store.
     *
     * @param tenant             the tenant, which the bindings name.
     * @param environment        the deployment environment.
     * @param compiled           the record's compiled contract.
     * @param signed             the record's signed envelope.
     * @param signedEnvelope     the signed envelope's exact bytes.
     * @param profile            the record's substrate profile.
     * @param profileBytes       the substrate profile's exact bytes.
     * @param selected           the contract's selections resolved in the tenant's admitted catalog.
     * @param signer             Studio's signer, which signs the DCP runtime bundle as compile does.
     * @param trustKeysDir       the configured public trust-key folder.
     * @param keys               the keys loaded from it, which the pipeline verifies the contract with.
     * @param hostedCatalogJars  the hosted components' own jars, by file name (read for their packaged configuration
     *                           schemas); empty when the contract hosts nothing.
     * @param pinnedJars         every jar the hosted contracts pin, companions included, by file name; empty when the
     *                           contract hosts nothing.
     * @param providerNodes      the draft node of each grounded claim URI.
     */
    record Inputs(String tenant, StudioDeploymentEnvironment environment, CompiledContract compiled,
                  SignedFabricContract signed, byte[] signedEnvelope, SubstrateProfile profile, byte[] profileBytes,
                  SelectedCatalogEntries selected, FabricContractSigner signer, Path trustKeysDir,
                  VerificationKeySet keys, Map<String, Path> hostedCatalogJars, Map<String, Path> pinnedJars,
                  Map<URI, String> providerNodes) {
    }

    /**
     * What a run found: either the refusal, or the emit facts.
     *
     * @param absentReason       the refusal, or null when the emit ran.
     * @param targetKind         the emitter's target kind.
     * @param artifacts          the emitted artifacts.
     * @param requiredSecrets    the secret requests.
     * @param runtimeBindings    the generated bindings, by reference names.
     * @param bindingsSha256     the generated set's hash.
     * @param emitManifestSha256 the emit manifest's hash.
     */
    record Outcome(StudioAbsentReason absentReason, String targetKind, List<StudioEmitPlanPreview.Artifact> artifacts,
                   List<StudioEmitPlanPreview.SecretRequest> requiredSecrets,
                   List<StudioEmitPlanPreview.Binding> runtimeBindings, String bindingsSha256,
                   String emitManifestSha256) {
        static Outcome refused(String code, String message) {
            return new Outcome(new StudioAbsentReason(code, message), null, List.of(), List.of(), List.of(), null, null);
        }
    }

    /** A step's refusal, carried to {@link #run} with its code. */
    private static final class Refused extends RuntimeException {
        private final String code;

        Refused(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /**
     * Runs the preview's steps in a fresh temporary folder, deleted afterwards whatever the outcome.
     *
     * @param inputs what the run reads.
     * @return the refusal or the emit facts.
     */
    Outcome run(Inputs inputs) {
        Path work = null;
        try {
            work = createWorkFolder();
            Path signedPath = write(work.resolve(SOURCE_SIGNED_CONTRACT), inputs.signedEnvelope());
            Path profilePath = write(work.resolve("substrate-profile.yaml"), inputs.profileBytes());
            Path bundlePath = write(work.resolve("dcp-runtime-bundle.zip"), bundle(inputs));
            Path hostedCatalog = inputs.hostedCatalogJars().isEmpty() ? null : copyJars(work.resolve("hosted-catalog"), inputs.hostedCatalogJars());
            Path pinned = inputs.pinnedJars().isEmpty() ? null : copyJars(work.resolve("component-artifacts"), inputs.pinnedJars());
            byte[] bindingYaml = bindings(inputs, work, hostedCatalog);
            Path bindingPath = write(work.resolve("runtime-binding.yaml"), bindingYaml);
            DeploymentTarget target = EmitTargetInputs.forTarget(inputs.environment().target().deploymentTarget())
                    .contract(signedPath)
                    .profile(profilePath)
                    .runtimeBinding(bindingPath)
                    .dcpRuntimeBundle(bundlePath)
                    .componentArtifacts(pinned)
                    .trustKeys(inputs.trustKeysDir())
                    .build();
            EmitResult result = emit(inputs, target, Files.createDirectories(work.resolve("out")));
            if (result instanceof EmitResult.Unsupported unsupported) {
                return Outcome.refused(EMIT_SHAPE_UNSUPPORTED, unsupported.detail() + " (shapes " + unsupported.shapes() + ")");
            }
            EmitResult.Ok ok = (EmitResult.Ok) result;
            return new Outcome(null, ok.manifest().targetKind(),
                    ok.artifacts().stream().map(a -> new StudioEmitPlanPreview.Artifact(
                            a.path().toString().replace('\\', '/'), a.kind().name(), a.sha256())).toList(),
                    ok.manifest().requiredSecrets().stream().map(s -> new StudioEmitPlanPreview.SecretRequest(
                            s.name(), s.boundComponentId(), s.targetStoreHint())).toList(),
                    summarize(RuntimeBindingCodec.read(bindingYaml), inputs),
                    sha256(bindingYaml), ok.manifest().emitManifestSha256());
        } catch (Refused refused) {
            return Outcome.refused(refused.code, refused.getMessage());
        } catch (IOException io) {
            throw new IllegalStateException("the emit-plan preview could not use its temporary folder: " + io.getMessage(), io);
        } finally {
            if (work != null) {
                deleteRecursively(work);
            }
        }
    }

    /** Step: the DCP runtime bundle, signed by Studio's signer exactly as compile writes it. */
    private static byte[] bundle(Inputs inputs) {
        try {
            return new com.unfurl.fabric.compile.DcpRuntimeBundleWriter().write(inputs.compiled(), inputs.selected().entries(),
                    inputs.signer(), inputs.signer().keyFingerprint());
        } catch (RuntimeException failed) {
            throw refused(BUNDLE_FAILED, failed);
        }
    }

    /**
     * Step: the runtime bindings, generated from the environment's inputs the way {@code fabric.runtime-binding-generate}
     * reads the same arguments. The documentation visibility text is written into the run's folder for the reader.
     */
    private byte[] bindings(Inputs inputs, Path work, Path hostedCatalog) throws IOException {
        StudioDeploymentEnvironment environment = inputs.environment();
        Path visibility = null;
        if (environment.bindings().documentation() != null && environment.bindings().documentation().visibility() != null) {
            visibility = write(work.resolve("documentation-visibility.yaml"),
                    environment.bindings().documentation().visibility().getBytes(StandardCharsets.UTF_8));
        }
        String targetEnvironment = environment.target().environment() == null ? environment.name()
                : environment.target().environment();
        try {
            RuntimeBindingInputs bindingInputs = RuntimeBindingInputs.fromMap(
                    environment.bindings().toolArguments(inputs.tenant(), targetEnvironment, visibility), Path::of);
            var configuration = HostedComponentConfiguration.forContract(inputs.compiled(), hostedCatalog,
                    bindingInputs.componentConfig());
            return generator.get().generate(inputs.signed(), inputs.selected(),
                    bindingInputs.options(SOURCE_SIGNED_CONTRACT, sha256(inputs.signedEnvelope()), inputs.signed(), configuration));
        } catch (HostedComponentConfiguration.Refusal refusal) {
            throw new Refused(HOSTED_CONFIGURATION + refusal.reason().name(), refusal.getMessage());
        } catch (RuntimeBindingInputs.Invalid invalid) {
            throw new Refused(StudioDeploymentEnvironments.INVALID, invalid.argument() + ": " + invalid.getMessage());
        } catch (RuntimeException failed) {
            throw refused(BINDING_FAILED, failed);
        }
    }

    /** Step: the emit itself, through the installed emitter for the target's kind and the pipeline's preflight. */
    private EmitResult emit(Inputs inputs, DeploymentTarget target, Path out) {
        try {
            DeployEmitter backend = bootstrap.get().create(new EmitterConfig(target, target.options(), true));
            return new EmitPipeline().emit(new EmitRequest(inputs.signed(), inputs.profile(), target, out), backend, inputs.keys());
        } catch (EmitterBootstrapException unsupported) {
            throw new Refused(EMIT_TARGET_UNSUPPORTED, unsupported.getMessage());
        } catch (EmitPreflightException preflight) {
            throw new Refused(EMIT_PREFLIGHT_FAILED, preflight.getMessage());
        } catch (RuntimeException failed) {
            throw refused(EMIT_FAILED, failed);
        }
    }

    /**
     * Projector: the generated set as the preview reports it, by reference names only. A binding's capability is its
     * contract's provider capability, and its node the draft node of its provider's claim URI.
     */
    private static List<StudioEmitPlanPreview.Binding> summarize(RuntimeBindingSet set, Inputs inputs) {
        Map<URI, String> capabilities = new LinkedHashMap<>();
        for (CompositionContract child : inputs.compiled().childContracts()) {
            if (child.binding() != null) capabilities.put(child.contractId(), child.binding().providerCapability());
        }
        List<StudioEmitPlanPreview.Binding> bindings = new ArrayList<>();
        for (RuntimeBinding binding : set.runtimeBindings()) {
            var provider = binding.providerInstance();
            URI component = provider == null ? null : provider.componentUri();
            List<String> secretRefs = new ArrayList<>(binding.configuration() == null ? List.of()
                    : binding.configuration().secretRefs().keySet().stream().sorted().toList());
            if (provider != null && provider.credentialsRef() != null) secretRefs.add("credentials");
            List<String> configRefs = new ArrayList<>(binding.configuration() == null ? List.of()
                    : binding.configuration().configRefs().keySet().stream().sorted().toList());
            if (provider != null && provider.baseUrlRef() != null) configRefs.add("baseUrl");
            var policy = binding.runtimePolicy();
            String capability = capabilities.get(binding.contractId());
            bindings.add(new StudioEmitPlanPreview.Binding(
                    String.valueOf(binding.bindingId()),
                    String.valueOf(binding.contractId()),
                    capability,
                    component == null ? null : component.toString(),
                    capability == null || component == null ? null : inputs.providerNodes().get(component),
                    provider == null || provider.deploymentKind() == null ? null : provider.deploymentKind().name(),
                    provider == null || provider.baseUrl() == null ? null : provider.baseUrl().toString(),
                    policy != null && policy.enabled(),
                    policy == null ? null : policy.timeoutMs(),
                    policy != null && policy.auditEnabled(),
                    policy == null ? null : policy.telemetryNamespace(),
                    secretRefs,
                    configRefs));
        }
        bindings.sort(Comparator.comparing(StudioEmitPlanPreview.Binding::contractId));
        return bindings;
    }

    /** A step's failure as a refusal: the code its message starts with, or the step's code when it names none. */
    private static Refused refused(String stepCode, RuntimeException failed) {
        String message = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
        Matcher coded = CODED.matcher(message);
        return coded.matches() ? new Refused(coded.group(1), coded.group(2)) : new Refused(stepCode, message);
    }

    /** A fresh, uniquely named folder, owner-only where POSIX permissions exist. */
    private static Path createWorkFolder() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory("studio-emit-plan-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory("studio-emit-plan-");
    }

    /** Copies jars into a folder under their file names. */
    private static Path copyJars(Path folder, Map<String, Path> jars) throws IOException {
        Files.createDirectories(folder);
        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            Files.copy(jar.getValue(), folder.resolve(jar.getKey()));
        }
        return folder;
    }

    private static Path write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.write(path, bytes);
    }

    /** Deletes the run's folder, deepest entries first; a failure to delete is an error, never ignored. */
    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException io) {
            throw new IllegalStateException("the emit-plan preview could not delete its temporary folder " + root + ": "
                    + io.getMessage(), io);
        }
    }

    /** Lower-case hex SHA-256. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
