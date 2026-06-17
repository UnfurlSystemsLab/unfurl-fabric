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
            Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
            EmittedArtifact artifact = new EmittedArtifact(
                    request.outDir().relativize(path),
                    sha256(Files.readAllBytes(path)),
                    ArtifactKind.RUNTIME_PROFILE);
            EmitManifest manifest = new EmitManifest(
                    request.contract().canonicalHash(),
                    config.target().kind(),
                    List.of(artifact),
                    List.of(),
                    sha256((request.contract().canonicalHash() + "|" + artifact.path()).getBytes(StandardCharsets.UTF_8)));
            return new EmitResult.Ok(List.of(artifact), manifest);
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
