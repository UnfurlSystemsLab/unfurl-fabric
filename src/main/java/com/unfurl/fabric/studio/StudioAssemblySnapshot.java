package com.unfurl.fabric.studio;

import java.util.List;

/**
 * Data Transfer Object: portable tenant/assembly workspace snapshot. It captures
 * the durable Studio assembly summary, layout state, and draft sessions while
 * leaving contract validity to Fabric's normal DCP validation paths after load.
 */
public record StudioAssemblySnapshot(
        String tenantId,
        String assemblyId,
        StudioAssemblySummary assembly,
        StudioLayoutState layout,
        List<StudioDraftSession> sessions,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    public StudioAssemblySnapshot {
        tenantId = tenantId == null || tenantId.isBlank() ? "tenant-local" : tenantId;
        assemblyId = assemblyId == null || assemblyId.isBlank() ? "assembly-demo" : assemblyId;
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }
}
