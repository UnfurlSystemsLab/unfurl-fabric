package com.unfurl.fabric.studio;

import java.util.List;

public record StudioReplacementCandidatesResponse(
        String tenantId,
        String assemblyId,
        String componentNodeId,
        List<StudioReplacementCandidate> candidates,
        List<String> warnings
) {
    public StudioReplacementCandidatesResponse {
        tenantId = tenantId == null ? "" : tenantId;
        assemblyId = assemblyId == null ? "" : assemblyId;
        componentNodeId = componentNodeId == null ? "" : componentNodeId;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
