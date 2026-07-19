package com.unfurl.fabric.studio;

import java.time.Instant;

/**
 * Data Transfer Object: immutable tenant file-version row for Studio-visible
 * catalog inputs and generated artifacts.
 *
 * <p>Pattern: read-model table row. The record mirrors the intended
 * `studio_files` database schema while remaining persistence-neutral for the
 * current lightweight Studio state store. Invariants: `tenantId` scopes every
 * row, `fileId` identifies one immutable version, and `logicalFileId` groups
 * successive versions of the same operator-facing file.
 */
public record StudioFileRecord(
        String fileId,
        String tenantId,
        String logicalFileId,
        int version,
        String fileName,
        String fileTitle,
        String filePath,
        String fileType,
        String mediaType,
        String sha256,
        Instant createdAt
) {
    /**
     * Data Transfer Object invariant: normalizes optional metadata while keeping
     * file ids and tenant ids explicit for tenant-isolated lookups.
     */
    public StudioFileRecord {
        fileId = fileId == null ? "" : fileId;
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        logicalFileId = logicalFileId == null || logicalFileId.isBlank() ? fileId : logicalFileId;
        version = Math.max(1, version);
        fileName = fileName == null ? "" : fileName;
        fileTitle = fileTitle == null || fileTitle.isBlank() ? fileName : fileTitle;
        filePath = filePath == null ? "" : filePath;
        fileType = fileType == null || fileType.isBlank() ? "OTHER" : fileType;
        mediaType = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        sha256 = sha256 == null ? "" : sha256;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }
}
