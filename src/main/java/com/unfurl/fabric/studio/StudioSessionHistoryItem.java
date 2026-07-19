package com.unfurl.fabric.studio;

import java.time.Instant;

/**
 * Data Transfer Object: compact tenant session history row for Studio sidebars
 * and artifact browsers.
 *
 * <p>Pattern: projector output. The service derives each item from the
 * authoritative `StudioDraftSession` plus session-file links, so the UI can
 * show historical drafts without loading every diagnostic artifact.
 */
public record StudioSessionHistoryItem(
        String tenantId,
        String assemblyId,
        String sessionId,
        String displayName,
        String sessionType,
        String status,
        String baseCatalogHash,
        String catalogFileId,
        Instant createdAt,
        Instant updatedAt,
        Instant lastOpenedAt,
        int linkedFileCount,
        int intentCount
) {
    /**
     * Data Transfer Object invariant: normalizes nullable persisted fields for
     * stable JSON output and deterministic client rendering.
     */
    public StudioSessionHistoryItem {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        sessionId = sessionId == null ? "" : sessionId;
        displayName = displayName == null || displayName.isBlank() ? sessionId : displayName;
        sessionType = sessionType == null || sessionType.isBlank() ? "DRAFT" : sessionType;
        status = status == null || status.isBlank() ? "OPEN" : status;
        baseCatalogHash = baseCatalogHash == null ? "" : baseCatalogHash;
        catalogFileId = catalogFileId == null ? "" : catalogFileId;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        lastOpenedAt = lastOpenedAt == null ? updatedAt : lastOpenedAt;
        linkedFileCount = Math.max(0, linkedFileCount);
        intentCount = Math.max(0, intentCount);
    }
}
