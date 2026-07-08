package com.unfurl.fabric.studio;

/**
 * Data Transfer Object: one inline source file supplied to Studio needs extraction.
 *
 * <p>Pattern: immutable API DTO. The file name identifies the source type (`workflow.yaml`,
 * `*.agent.yaml`, etc.) and `content` carries UTF-8 YAML/text for analyzers. Empty content is
 * allowed so clients can share names before they can upload full files.
 *
 * @param fileName source file name or relative path.
 * @param content  UTF-8 source content used by content-aware analyzers.
 */
public record StudioNeedsExtractionSourceFile(
        String fileName,
        String content
) {
    /** Compact constructor: trims the name and normalizes null content to an empty string. */
    public StudioNeedsExtractionSourceFile {
        fileName = fileName == null ? "" : fileName.trim();
        content = content == null ? "" : content;
    }
}
