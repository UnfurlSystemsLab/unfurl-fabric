package com.unfurl.fabric.studio;

public record StudioDynamicDcpEdge(
        String fromNodeId,
        String toNodeId,
        String relationship
) {
    public StudioDynamicDcpEdge {
        if (fromNodeId == null || fromNodeId.isBlank()) {
            throw new IllegalArgumentException("fromNodeId is required");
        }
        if (toNodeId == null || toNodeId.isBlank()) {
            throw new IllegalArgumentException("toNodeId is required");
        }
        relationship = relationship == null || relationship.isBlank() ? "CONTAINS" : relationship;
    }
}
