package com.unfurl.fabric.studio;

public record StudioComponentArtifactDraft(
        String fileName,
        String sha256,
        String claimYaml,
        String artifactBase64
) {
    /**
     * Backward-compatible constructor for older tests and clients that only passed artifact
     * identity. Such drafts will be rejected by admission until DCP claim YAML or JAR bytes
     * are provided.
     */
    public StudioComponentArtifactDraft(String fileName, String sha256) {
        this(fileName, sha256, "", "");
    }

    /**
     * Backward-compatible constructor for clients that provide explicit claim YAML rather
     * than archive bytes. The canonical record still has room for JAR upload content.
     */
    public StudioComponentArtifactDraft(String fileName, String sha256, String claimYaml) {
        this(fileName, sha256, claimYaml, "");
    }

    /**
     * Data Transfer Object invariant: trim artifact metadata but preserve claim YAML and
     * base64 archive bytes exactly enough for the DCP parser and archive decoder to report
     * meaningful diagnostics.
     */
    public StudioComponentArtifactDraft {
        fileName = fileName == null ? "" : fileName.trim();
        sha256 = sha256 == null ? "" : sha256.trim();
        claimYaml = claimYaml == null ? "" : claimYaml;
        artifactBase64 = artifactBase64 == null ? "" : artifactBase64.trim();
    }
}
