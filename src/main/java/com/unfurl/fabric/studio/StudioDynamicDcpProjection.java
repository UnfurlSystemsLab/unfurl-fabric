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
        // Runtime capabilities exposed by the selected Unfurl
        // substrate. Component needs marked ?substrate=true or
        // ?owner=substrate bind to these ports.
        List<StudioSubstratePort> substratePorts,
        // Port-level wirings resolved by walking every draft node's
        // OFFER ports against every other draft node's DEPENDENCY ports,
        // plus substrate-port bindings for substrate-owned needs. Drives
        // the Studio 3D scene's pipe rendering; ?owner=host and
        // ?owner=fabric needs are skipped (they're external to the draft).
        List<StudioPortConnectionEdge> connections,
        List<String> warnings,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /**
     * Data Transfer Object invariant: normalizes projection defaults and freezes graph
     * collections plus optional downloadable diagnostic metadata.
     */
    public StudioDynamicDcpProjection {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        compositionMode = compositionMode == null || compositionMode.isBlank() ? "DYNAMIC" : compositionMode;
        rootNodeId = rootNodeId == null || rootNodeId.isBlank() ? "company:local" : rootNodeId;
        focusNodeId = focusNodeId == null || focusNodeId.isBlank() ? rootNodeId : focusNodeId;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        substratePorts = substratePorts == null ? List.of() : List.copyOf(substratePorts);
        connections = connections == null ? List.of() : List.copyOf(connections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
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
                nodes, edges, List.of(), List.of(), warnings, List.of());
    }

    public StudioDynamicDcpProjection(
            String tenantId,
            String assemblyId,
            String compositionMode,
            String rootNodeId,
            String focusNodeId,
            List<StudioDynamicDcpNode> nodes,
            List<StudioDynamicDcpEdge> edges,
            List<StudioPortConnectionEdge> connections,
            List<String> warnings
    ) {
        this(tenantId, assemblyId, compositionMode, rootNodeId, focusNodeId,
                nodes, edges, List.of(), connections, warnings, List.of());
    }

    /**
     * Backward-compatible constructor for callers that include substrate ports and
     * connections but do not emit downloadable projection diagnostics.
     */
    public StudioDynamicDcpProjection(
            String tenantId,
            String assemblyId,
            String compositionMode,
            String rootNodeId,
            String focusNodeId,
            List<StudioDynamicDcpNode> nodes,
            List<StudioDynamicDcpEdge> edges,
            List<StudioSubstratePort> substratePorts,
            List<StudioPortConnectionEdge> connections,
            List<String> warnings
    ) {
        this(tenantId, assemblyId, compositionMode, rootNodeId, focusNodeId,
                nodes, edges, substratePorts, connections, warnings, List.of());
    }
}
