package com.unfurl.fabric.cli;

import com.unfurl.deploy.spi.ApplyRequest;
import com.unfurl.deploy.spi.ApplyResult;
import com.unfurl.deploy.spi.ArtifactKind;
import com.unfurl.deploy.spi.DeployEmitter;
import com.unfurl.deploy.spi.EmitManifest;
import com.unfurl.deploy.spi.EmitRequest;
import com.unfurl.deploy.spi.EmitResult;
import com.unfurl.deploy.spi.EmittedArtifact;
import com.unfurl.deploy.spi.EmitterConfig;
import com.unfurl.deployment.domain.DeploymentShape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class TestDeployEmitter implements DeployEmitter {
    private final EmitterConfig config;

    TestDeployEmitter(EmitterConfig config) {
        this.config = config;
    }

    @Override
    public EmitResult emit(EmitRequest request) {
        try {
            Files.createDirectories(request.outDir());
            Path path = request.outDir().resolve(config.target().kind() + "-artifact.txt");
            StringBuilder content = new StringBuilder("target=" + config.target().kind() + "\n");
            appendOption(content, "fabricContractPath");
            appendOption(content, "substrateProfilePath");
            appendOption(content, "runtimeBindingPath");
            appendOption(content, "dcpRuntimeBundlePath");
            appendOption(content, "flowWorkflowsPath");
            appendOption(content, "componentArtifactsPath");
            appendOption(content, "flowConfigPath");
            appendOption(content, "flowSecretsPath");
            Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
            EmittedArtifact artifact = new EmittedArtifact(
                    request.outDir().relativize(path),
                    sha256(Files.readAllBytes(path)),
                    ArtifactKind.RUNTIME_PROFILE);
            List<EmittedArtifact> artifacts = new java.util.ArrayList<>(List.of(artifact));
            artifacts.addAll(hostedExports(request));
            EmitManifest manifest = new EmitManifest(
                    request.contract().canonicalHash(),
                    config.target().kind(),
                    artifacts,
                    List.of(),
                    sha256((request.contract().canonicalHash() + "|" + artifact.path()).getBytes(StandardCharsets.UTF_8)));
            return new EmitResult.Ok(artifacts, manifest);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public ApplyResult apply(ApplyRequest request) {
        if (!request.dryRun()) {
            try {
                Files.writeString(request.planDir().resolve("apply-marker.txt"),
                        request.target().kind(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return new ApplyResult.Ok(List.of(
                new ApplyResult.ResourceStatus(request.target().kind(), request.dryRun() ? "dry-run" : "applied")), request.dryRun());
    }

    @Override
    public Set<DeploymentShape> supportedShapes() {
        return switch (config.target().kind()) {
            case "local-compose" -> Set.of(
                    DeploymentShape.IN_PROCESS_LIBRARY,
                    DeploymentShape.MODULAR_MONOLITH_MODULE,
                    DeploymentShape.STANDALONE_JAVA_APP,
                    DeploymentShape.SPRING_BOOT_SERVICE);
            case "k8s" -> Set.of(
                    DeploymentShape.CONTAINERIZED_SERVICE,
                    DeploymentShape.SPRING_BOOT_SERVICE);
            case "remote" -> Set.of(
                    DeploymentShape.REMOTE_MICROSERVICE,
                    DeploymentShape.MANAGED_EXTERNAL_ADAPTER);
            default -> EnumSet.noneOf(DeploymentShape.class);
        };
    }

    /**
     * Test double of the real emitter's export (HLD-E §4.0b): when Fabric passed {@code componentArtifactsPath}, one
     * {@code hosted-exports/<slug>/host.json} per host of a hosted entry, so the CLI's operator-delivery lines can be
     * checked without the real overlay renderer (tested in unfurl-deploy-emitter). The host identity written is the
     * host's component id, version {@code test}.
     */
    private List<EmittedArtifact> hostedExports(EmitRequest request) throws IOException {
        if (config.target().options().get("componentArtifactsPath") == null || request.contract().contract().bindingPlan() == null) {
            return List.of();
        }
        List<EmittedArtifact> exports = new java.util.ArrayList<>();
        for (String host : request.contract().contract().bindingPlan().entries().stream()
                .map(entry -> entry.host()).flatMap(java.util.Optional::stream)
                .map(host -> host instanceof com.unfurl.deployment.domain.ComponentHost.InternalHost internal
                        ? internal.componentId() : host.toString())
                .distinct().sorted().toList()) {
            Path root = Files.createDirectories(request.outDir().resolve("hosted-exports").resolve(host.replaceAll("[^A-Za-z0-9]+", "-")));
            Path hostJson = Files.writeString(root.resolve("host.json"),
                    "{\"claimUri\":\"" + host + "\",\"claimVersion\":\"test\"}", StandardCharsets.UTF_8);
            exports.add(new EmittedArtifact(request.outDir().relativize(root), sha256(Files.readAllBytes(hostJson)),
                    ArtifactKind.HOSTED_COMPONENT_EXPORT));
        }
        return exports;
    }

    private void appendOption(StringBuilder sb, String key) {
        String value = config.target().options().get(key);
        if (value != null) {
            sb.append(key).append('=').append(value).append('\n');
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
