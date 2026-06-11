package com.unfurl.fabric.studio;

/**
 * One candidate wiring between the hovered palette entry and an existing
 * draft node — produced by {@code StudioCatalogService.connectionCandidates}
 * so the Studio UI can highlight compatible ports without committing the
 * Add intent first.
 *
 * <p>{@code direction} is {@code "CANDIDATE_OFFERS"} when the candidate's
 * OFFER port satisfies a draft node's DEPENDENCY port, or
 * {@code "CANDIDATE_NEEDS"} when a draft node's OFFER satisfies the
 * candidate's DEPENDENCY. {@code targetPortId} is the existing draft
 * node's port; {@code candidatePortId} is the hovered entry's port.
 */
public record StudioConnectionEdge(
        String targetNodeId,
        String targetPortId,
        String candidatePortId,
        String direction,
        String status,
        String reason
) {
    public StudioConnectionEdge {
        targetNodeId = targetNodeId == null ? "" : targetNodeId;
        targetPortId = targetPortId == null ? "" : targetPortId;
        candidatePortId = candidatePortId == null ? "" : candidatePortId;
        direction = direction == null || direction.isBlank() ? "CANDIDATE_OFFERS" : direction;
        status = status == null || status.isBlank() ? "BLOCKED" : status;
        reason = reason == null ? "" : reason;
    }
}
