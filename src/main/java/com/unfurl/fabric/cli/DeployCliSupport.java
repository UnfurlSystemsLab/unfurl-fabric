package com.unfurl.fabric.cli;

import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.deploy.core.ApplyDriver;
import com.unfurl.deploy.core.EmitPipeline;
import com.unfurl.deploy.core.EmitterBootstrap;
import com.unfurl.deploy.core.StitchApplyDriver;
import com.unfurl.deploy.core.StitchApplyResult;
import com.unfurl.deploy.core.StitchDriver;
import com.unfurl.deploy.core.StitchRequest;
import com.unfurl.deploy.core.StitchResult;
import com.unfurl.deploy.spi.ApplyRequest;
import com.unfurl.deploy.spi.ApplyResult;
import com.unfurl.deploy.spi.ArtifactKind;
import com.unfurl.deploy.spi.DeployEmitter;
import com.unfurl.deploy.spi.EmitRequest;
import com.unfurl.deploy.spi.EmitResult;
import com.unfurl.deploy.spi.EmittedArtifact;
import com.unfurl.deploy.spi.EmitterConfig;
import com.unfurl.deployment.policy.DeploymentTarget;
import com.unfurl.deployment.serialization.DeploymentTargetCodec;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.fabric.verify.TrustKeyDirectoryLoader;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


final class DeployCliSupport {

    static EmitOutcome emit(CliArgs args) {
        Path outDir = args.requiredPath("out");
        Path contractPath = args.requiredPath("contract");
        Path profilePath = args.requiredPath("profile");
        Path trustKeysPath = args.requiredPath("trust-keys");
        SignedFabricContract signed = readSigned(contractPath);
        Path componentArtifacts = componentArtifacts(args, signed);
        DeploymentTarget target = withComposeRuntimePaths(
                loadTarget(args.get("target")),
                contractPath,
                profilePath,
                args.optionalPath("runtime-binding"),
                args.optionalPath("dcp-runtime-bundle"),
                args.optionalPath("flow-workflows"),
                args.optionalPath("foundry-deployment-root"),
                componentArtifacts,
                trustKeysPath);
        EmitterConfig config = new EmitterConfig(target, target.options(), true);
        DeployEmitter backend = new EmitterBootstrap().create(config);
        EmitRequest request = new EmitRequest(
                signed,
                readProfile(profilePath),
                target,
                outDir);
        VerificationKeySet keys = new TrustKeyDirectoryLoader().load(trustKeysPath);
        EmitResult result = new EmitPipeline().emit(request, backend, keys);
        return new EmitOutcome(outDir, target, backend, result);
    }

    static StitchOutcome stitch(CliArgs args) {
        Path outDir = args.requiredPath("out");
        Path contractPath = args.requiredPath("contract");
        Path profilePath = args.requiredPath("profile");
        Path trustKeysPath = args.requiredPath("trust-keys");
        SignedFabricContract signed = readSigned(contractPath);
        Path componentArtifacts = componentArtifacts(args, signed);
        List<DeploymentTarget> targets = loadTargets(args.get("targets")).stream()
                .map(target -> withComposeRuntimePaths(
                        target,
                        contractPath,
                        profilePath,
                        args.optionalPath("runtime-binding"),
                        args.optionalPath("dcp-runtime-bundle"),
                        args.optionalPath("flow-workflows"),
                        args.optionalPath("foundry-deployment-root"),
                        componentArtifacts,
                        trustKeysPath))
                .toList();
        VerificationKeySet keys = new TrustKeyDirectoryLoader().load(trustKeysPath);
        StitchResult result = new StitchDriver().stitch(new StitchRequest(
                signed,
                readProfile(profilePath),
                targets,
                outDir),
                keys);
        return new StitchOutcome(outDir, targets, result);
    }

    static StitchApplyResult applyStitched(StitchOutcome outcome, boolean dryRun) {
        return new StitchApplyDriver().apply(
                outcome.outDir(), outcome.targets(), outcome.result().manifest(), dryRun);
    }

    /**
     * Threads the operator's signed-contract, substrate-profile, and optional
     * Flowfoundry deployment-root handoff paths into a {@code local-compose}
     * target's options. The compose backend uses these options to emit a STRICT
     * runtime profile and, when the full handoff set is present, a Flow
     * deployment root. Public DCP trust keys are also threaded through for Flow runtime
     * verification. Explicit values in the target file win ({@code putIfAbsent}). The catalog folder becomes
     * {@code componentArtifactsPath} for the admitted-component overlay (HLD-in-process-component-hosting §8).
     */
    private static DeploymentTarget withComposeRuntimePaths(
            DeploymentTarget target,
            Path contract,
            Path profile,
            Path runtimeBinding,
            Path dcpRuntimeBundle,
            Path flowWorkflows,
            Path foundryDeploymentRoot,
            Path componentArtifacts,
            Path trustKeysPath) {
        if (!"local-compose".equals(target.kind())) {
            return target;
        }
        Map<String, String> options = new LinkedHashMap<>(target.options());
        options.putIfAbsent("fabricContractPath", contract.toAbsolutePath().toString());
        options.putIfAbsent("substrateProfilePath", profile.toAbsolutePath().toString());
        putIfPresent(options, "runtimeBindingPath", runtimeBinding);
        putIfPresent(options, "dcpRuntimeBundlePath", dcpRuntimeBundle);
        putIfPresent(options, "flowWorkflowsPath", flowWorkflows);
        putIfPresent(options, "foundryDeploymentRootPath", foundryDeploymentRoot);
        putIfPresent(options, "componentArtifactsPath", componentArtifacts);
        putIfPresent(options, "trustKeysPath", trustKeysPath);
        return new DeploymentTarget(
                target.kind(),
                target.environment(),
                target.region(),
                target.endpoint(),
                options,
                target.credentialRefs());
    }

    /**
     * Overlay input (HLD-in-process-component-hosting §8): a signed plan with hosted in-process components needs
     * {@code --catalog}, the local catalog folder its pinned jars are copied from; a plan without them refuses it, the
     * same rule the emitter applies, so the operator learns it before any target runs.
     *
     * @return the catalog folder, or {@code null} when the plan hosts nothing
     */
    private static Path componentArtifacts(CliArgs args, SignedFabricContract signed) {
        var plan = signed.contract().bindingPlan();
        List<String> hosted = plan == null ? List.of() : plan.entries().stream()
                .filter(entry -> entry.host().isPresent()).map(entry -> entry.componentId()).distinct().sorted().toList();
        Path catalog = args.optionalPath("catalog");
        if (hosted.isEmpty()) {
            if (catalog != null) {
                throw FabricCliException.usage("--catalog is read for hosted in-process components, and the signed plan has none");
            }
            return null;
        }
        if (catalog == null) {
            throw FabricCliException.usage("the signed plan hosts in-process components " + hosted
                    + "; --catalog must name the catalog folder their pinned jars are copied from");
        }
        return catalog;
    }

    /**
     * Visible operator step (HLD-E §4.0b): one line per admitted-component export, naming where it is and the host
     * identity from its {@code host.json}; an export is never mounted or started, so the operator must deliver it.
     */
    private static void printOperatorDeliveries(Path dir, List<EmittedArtifact> artifacts, PrintStream out) {
        for (EmittedArtifact artifact : artifacts) {
            if (artifact.kind() != ArtifactKind.HOSTED_COMPONENT_EXPORT) continue;
            Path export = dir.resolve(artifact.path());
            try {
                var host = new com.fasterxml.jackson.databind.ObjectMapper().readTree(export.resolve("host.json").toFile());
                out.println("operatorDelivery=" + export.toAbsolutePath().normalize()
                        + " host=" + host.path("claimUri").asText() + "@" + host.path("claimVersion").asText()
                        + " (not mounted or started: deliver it to that host)");
            } catch (IOException ex) {
                throw FabricCliException.runtime("unable to read " + export.resolve("host.json") + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Option helper: writes optional handoff paths as absolute values while respecting
     * target-file overrides.
     */
    private static void putIfPresent(Map<String, String> options, String key, Path path) {
        if (path != null) {
            options.putIfAbsent(key, path.toAbsolutePath().toString());
        }
    }

    static ApplyResult apply(Path planDir, DeploymentTarget target, boolean dryRun) {
        return new ApplyDriver().apply(planDir, target, dryRun);
    }

    static DeploymentTarget loadTarget(String value) {
        if (value == null || value.isBlank()) {
            throw FabricCliException.usage("missing required --target");
        }
        Path path = Path.of(value);
        try {
            String yaml = Files.exists(path)
                    ? Files.readString(path, StandardCharsets.UTF_8)
                    : readPreset(value);
            return new DeploymentTargetCodec().parse(yaml);
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read deployment target " + value + ": " + ex.getMessage());
        }
    }

    static List<DeploymentTarget> loadTargets(String value) {
        if (value == null || value.isBlank()) {
            throw FabricCliException.usage("missing required --targets");
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(DeployCliSupport::loadTarget)
                .toList();
    }

    static void printEmit(EmitOutcome outcome, PrintStream out) {
        out.println("emitted=" + outcome.outDir());
        out.println("targetKind=" + outcome.target().kind());
        if (outcome.result() instanceof EmitResult.Ok ok) {
            out.println("artifacts=" + ok.artifacts().size());
            ok.artifacts().forEach(artifact -> printArtifact(artifact, out));
            out.println("requiredSecrets=" + ok.manifest().requiredSecrets().size());
            out.println("emitManifestSha256=" + ok.manifest().emitManifestSha256());
            printOperatorDeliveries(outcome.outDir(), ok.artifacts(), out);
            return;
        }
        EmitResult.Unsupported unsupported = (EmitResult.Unsupported) outcome.result();
        out.println("unsupportedShapes=" + unsupported.shapes());
        out.println("detail=" + unsupported.detail());
    }

    static void printStitch(StitchOutcome outcome, PrintStream out) {
        out.println("stitched=" + outcome.outDir());
        out.println("targets=" + outcome.result().manifest().targets().size());
        outcome.result().manifest().targets().forEach(target -> out.println(
                "targetKind=" + target.kind()
                        + " dir=" + target.dir()
                        + " ownedShapes=" + target.ownedShapes()
                        + " subManifestSha256=" + target.subManifestSha256()));
        out.println("coverage=" + outcome.result().manifest().coverage().size());
        out.println("stitchSha256=" + outcome.result().manifest().stitchSha256());
        // Sub-emits follow target order and the manifest's targets are sorted, so pair them by sub-manifest hash.
        for (var target : outcome.result().manifest().targets()) {
            outcome.result().subEmits().stream()
                    .filter(sub -> sub.manifest().emitManifestSha256().equals(target.subManifestSha256()))
                    .findFirst()
                    .ifPresent(sub -> printOperatorDeliveries(outcome.outDir().resolve(target.dir()), sub.artifacts(), out));
        }
    }

    static void printStitchApply(StitchApplyResult result, PrintStream out) {
        out.println("stitchedApply=" + (result.ok() ? "ok" : "failed"));
        for (StitchApplyResult.TargetApply target : result.targets()) {
            if (target.result() instanceof ApplyResult.Ok ok) {
                out.println("apply target=" + target.kind() + " status=ok dryRun=" + ok.dryRun());
            } else {
                out.println("apply target=" + target.kind() + " status=failed detail="
                        + ((ApplyResult.Failed) target.result()).detail());
            }
        }
    }

    static void printApply(ApplyResult result, PrintStream out) {
        if (result instanceof ApplyResult.Ok ok) {
            out.println("apply=ok");
            out.println("dryRun=" + ok.dryRun());
            ok.resources().forEach(resource ->
                    out.println("resource=" + resource.name() + " status=" + resource.status()));
            return;
        }
        ApplyResult.Failed failed = (ApplyResult.Failed) result;
        out.println("apply=failed");
        out.println("detail=" + failed.detail());
    }

    static boolean cloudTarget(DeploymentTarget target) {
        return !"local-compose".equals(target.kind());
    }

    private static String readPreset(String name) throws IOException {
        String resource = "deploy-presets/" + name + ".yaml";
        try (InputStream in = DeployCliSupport.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw FabricCliException.usage("unknown deployment target or preset: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SignedFabricContract readSigned(Path path) {
        try {
            return new SignedFabricContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + path + ": " + ex.getMessage());
        }
    }

    private static SubstrateProfile readProfile(Path path) {
        try {
            return new SubstrateProfileCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read substrate profile " + path + ": " + ex.getMessage());
        }
    }

    private static void printArtifact(EmittedArtifact artifact, PrintStream out) {
        out.println("artifact=" + artifact.path() + " kind=" + artifact.kind() + " sha256=" + artifact.sha256());
    }

    record EmitOutcome(Path outDir, DeploymentTarget target, DeployEmitter backend, EmitResult result) {
    }

    record StitchOutcome(Path outDir, List<DeploymentTarget> targets, StitchResult result) {
    }

    private DeployCliSupport() {
    }
}
