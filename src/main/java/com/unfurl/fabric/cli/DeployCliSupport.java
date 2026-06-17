package com.unfurl.fabric.cli;

import com.unfurl.dcp.trust.VerificationKeySet;
import com.unfurl.deploy.core.EmitPipeline;
import com.unfurl.deploy.core.EmitterBootstrap;
import com.unfurl.deploy.core.StitchDriver;
import com.unfurl.deploy.core.StitchRequest;
import com.unfurl.deploy.core.StitchResult;
import com.unfurl.deploy.spi.ApplyRequest;
import com.unfurl.deploy.spi.ApplyResult;
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
import java.util.List;


final class DeployCliSupport {

    static EmitOutcome emit(CliArgs args) {
        Path outDir = args.requiredPath("out");
        DeploymentTarget target = loadTarget(args.get("target"));
        EmitterConfig config = new EmitterConfig(target, target.options(), true);
        DeployEmitter backend = new EmitterBootstrap().create(config);
        EmitRequest request = new EmitRequest(
                readSigned(args.requiredPath("contract")),
                readProfile(args.requiredPath("profile")),
                target,
                outDir);
        VerificationKeySet keys = new TrustKeyDirectoryLoader().load(args.requiredPath("trust-keys"));
        EmitResult result = new EmitPipeline().emit(request, backend, keys);
        return new EmitOutcome(outDir, target, backend, result);
    }

    static StitchOutcome stitch(CliArgs args) {
        Path outDir = args.requiredPath("out");
        List<DeploymentTarget> targets = loadTargets(args.get("targets"));
        VerificationKeySet keys = new TrustKeyDirectoryLoader().load(args.requiredPath("trust-keys"));
        StitchResult result = new StitchDriver().stitch(new StitchRequest(
                readSigned(args.requiredPath("contract")),
                readProfile(args.requiredPath("profile")),
                targets,
                outDir),
                keys);
        return new StitchOutcome(outDir, result);
    }

    static ApplyResult apply(Path planDir, DeploymentTarget target, boolean dryRun) {
        DeployEmitter backend = new EmitterBootstrap().create(new EmitterConfig(target, target.options(), true));
        return backend.apply(new ApplyRequest(planDir, target, dryRun));
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

    record StitchOutcome(Path outDir, StitchResult result) {
    }

    private DeployCliSupport() {
    }
}
