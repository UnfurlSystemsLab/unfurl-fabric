package com.unfurl.fabric.studio;

import java.util.List;

/**
 * Response shape for
 * {@code GET /studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/connection-candidates?catalogEntryId=...}.
 * Hover-preview surface for the Studio UI: enumerates the OFFER↔DEPENDENCY
 * wirings the candidate could make against the current draft (in
 * {@code connections}) and the existing draft slots the candidate could
 * substitute outright (in {@code replacements}). Both lists arrive in
 * one round trip so the UI renders both colours from a single hover.
 */
public record StudioConnectionCandidatesResponse(
        String tenantId,
        String assemblyId,
        String catalogEntryId,
        List<StudioConnectionEdge> connections,
        List<StudioReplacementEdge> replacements,
        List<String> warnings
) {
    public StudioConnectionCandidatesResponse {
        tenantId = tenantId == null ? "" : tenantId;
        assemblyId = assemblyId == null ? "" : assemblyId;
        catalogEntryId = catalogEntryId == null ? "" : catalogEntryId;
        connections = connections == null ? List.of() : List.copyOf(connections);
        replacements = replacements == null ? List.of() : List.copyOf(replacements);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
