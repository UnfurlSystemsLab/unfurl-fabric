package com.unfurl.fabric.studio;

public record StudioExportArtifact(
        String artifactId,
        String mediaType,
        String sha256,
        String url
) {
}
