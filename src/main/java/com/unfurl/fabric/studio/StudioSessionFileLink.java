package com.unfurl.fabric.studio;

import java.time.Instant;

/**
 * Data Transfer Object: tenant-scoped association between a Studio session or
 * correlation id and a file-version row.
 *
 * <p>Pattern: association-table read model. The same `tenantId` is present on
 * the link and the referenced file so persistence adapters can enforce tenant
 * isolation with a composite foreign key instead of relying on route context
 * alone.
 */
public record StudioSessionFileLink(
        String id,
        String tenantId,
        String sessionId,
        String correlationId,
        String fileId,
        String role,
        Instant createdAt
) {
    /**
     * Data Transfer Object invariant: keeps missing optional correlation data
     * empty while preserving explicit tenant/file identifiers.
     */
    public StudioSessionFileLink {
        id = id == null ? "" : id;
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        sessionId = sessionId == null ? "" : sessionId;
        correlationId = correlationId == null ? "" : correlationId;
        fileId = fileId == null ? "" : fileId;
        role = role == null || role.isBlank() ? "RELATED" : role;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }
}
