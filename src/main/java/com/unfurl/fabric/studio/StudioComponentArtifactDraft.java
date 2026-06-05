package com.unfurl.fabric.studio;

public record StudioComponentArtifactDraft(
        String fileName,
        String sha256
) {
    public StudioComponentArtifactDraft {
        fileName = fileName == null ? "" : fileName.trim();
        sha256 = sha256 == null ? "" : sha256.trim();
    }
}
