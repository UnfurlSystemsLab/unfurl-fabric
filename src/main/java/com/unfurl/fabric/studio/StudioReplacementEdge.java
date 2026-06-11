package com.unfurl.fabric.studio;

/**
 * One candidate slot-substitution between the hovered palette entry and
 * an existing draft node — produced by
 * {@code StudioCatalogService.connectionCandidates}. The candidate would
 * take the place of {@code targetNodeId} entirely (versus
 * {@link StudioConnectionEdge} which wires the two together).
 */
public record StudioReplacementEdge(
        String targetNodeId,
        String status,
        String reason
) {
    public StudioReplacementEdge {
        targetNodeId = targetNodeId == null ? "" : targetNodeId;
        status = status == null || status.isBlank() ? "BLOCKED" : status;
        reason = reason == null ? "" : reason;
    }
}
