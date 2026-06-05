package com.unfurl.fabric.studio;

import java.util.List;

public record StudioDynamicDcpNode(
        String nodeId,
        String label,
        String dcpType,
        String level,
        String catalogEntryId,
        List<String> capabilities,
        List<String> compatibleDescendants,
        boolean replacementAllowed
) {
    public StudioDynamicDcpNode {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }
        label = label == null || label.isBlank() ? nodeId : label;
        dcpType = dcpType == null || dcpType.isBlank() ? "COMPONENT" : dcpType;
        level = level == null || level.isBlank() ? "CHILD" : level;
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        compatibleDescendants = compatibleDescendants == null ? List.of() : List.copyOf(compatibleDescendants);
    }
}
