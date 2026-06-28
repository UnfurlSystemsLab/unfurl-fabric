package com.unfurl.fabric.studio;

public record StudioComponentArtifactDraft(
        String fileName,
        String sha256,
        String claimYaml
) {
    /**
     * Backward-compatible constructor for older tests and clients that only passed artifact
     * identity. Such drafts will be rejected by admission until DCP claim YAML is provided.
     */
    public StudioComponentArtifactDraft(String fileName, String sha256) {
        this(fileName, sha256, "");
    }

    /**
     * Data Transfer Object invariant: trim artifact metadata but preserve claim YAML bytes
     * exactly enough for the DCP parser to report meaningful line/path diagnostics.
     */
    public StudioComponentArtifactDraft {
        fileName = fileName == null ? "" : fileName.trim();
        sha256 = sha256 == null ? "" : sha256.trim();
        claimYaml = claimYaml == null ? "" : claimYaml;
    }
}
