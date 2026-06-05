package com.unfurl.fabric.studio;

import java.util.List;

public record StudioDynamicDcpProjection(
        String tenantId,
        String assemblyId,
        String compositionMode,
        String rootNodeId,
        String focusNodeId,
        List<StudioDynamicDcpNode> nodes,
        List<StudioDynamicDcpEdge> edges,
        List<String> warnings
) {
    public StudioDynamicDcpProjection {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        compositionMode = compositionMode == null || compositionMode.isBlank() ? "DYNAMIC" : compositionMode;
        rootNodeId = rootNodeId == null || rootNodeId.isBlank() ? "company:local" : rootNodeId;
        focusNodeId = focusNodeId == null || focusNodeId.isBlank() ? rootNodeId : focusNodeId;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
