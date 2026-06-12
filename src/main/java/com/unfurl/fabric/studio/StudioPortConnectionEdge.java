package com.unfurl.fabric.studio;

/**
 * One resolved port-to-port wiring in a draft assembly — emitted by
 * {@code StudioCatalogService.dynamicDcpProjection} so the Studio UI
 * can draw a pipe between the source's OFFER port and the target's
 * DEPENDENCY port without re-running the matcher client-side.
 *
 * <p>Unlike {@link StudioConnectionEdge} (which describes a single
 * hovered candidate's potential wirings against the draft), an instance
 * of this record represents an already-resolved declared dependency
 * between two nodes that are both in the draft.
 *
 * <p>{@code status} stays "ALLOWED" by construction today (the matcher
 * only emits matches that pass capability comparison); the field is
 * present so a future commit can surface mismatched-version or
 * refused-by-policy edges as BLOCKED without changing the response
 * shape.
 */
public record StudioPortConnectionEdge(
        String sourceNodeId,
        String sourcePortId,
        String targetNodeId,
        String targetPortId,
        String capability,
        String status,
        String reason
) {
    public StudioPortConnectionEdge {
        sourceNodeId = sourceNodeId == null ? "" : sourceNodeId;
        sourcePortId = sourcePortId == null ? "" : sourcePortId;
        targetNodeId = targetNodeId == null ? "" : targetNodeId;
        targetPortId = targetPortId == null ? "" : targetPortId;
        capability = capability == null ? "" : capability;
        status = status == null || status.isBlank() ? "ALLOWED" : status;
        reason = reason == null ? "" : reason;
    }
}
