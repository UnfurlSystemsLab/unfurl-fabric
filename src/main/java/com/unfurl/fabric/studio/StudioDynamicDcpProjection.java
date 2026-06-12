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
        // Port-level wirings resolved by walking every draft node's
        // OFFER ports against every other draft node's DEPENDENCY ports.
        // Drives the Studio 3D scene's pipe rendering; ?owner=host and
        // ?owner=fabric needs are skipped (they're external to the draft).
        List<StudioPortConnectionEdge> connections,
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
        connections = connections == null ? List.of() : List.copyOf(connections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * Backwards-compatible constructor for callers that pre-date the
     * connections field. Defaults connections to an empty list and
     * forwards to the canonical record constructor; lets us land the
     * new field without rewriting every existing call site.
     */
    public StudioDynamicDcpProjection(
            String tenantId,
            String assemblyId,
            String compositionMode,
            String rootNodeId,
            String focusNodeId,
            List<StudioDynamicDcpNode> nodes,
            List<StudioDynamicDcpEdge> edges,
            List<String> warnings
    ) {
        this(tenantId, assemblyId, compositionMode, rootNodeId, focusNodeId,
                nodes, edges, List.of(), warnings);
    }
}
