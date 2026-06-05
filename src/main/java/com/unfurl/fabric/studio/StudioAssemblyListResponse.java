package com.unfurl.fabric.studio;

import java.util.List;

public record StudioAssemblyListResponse(
        String tenantId,
        List<StudioAssemblySummary> assemblies
) {
    public StudioAssemblyListResponse {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        assemblies = assemblies == null ? List.of() : List.copyOf(assemblies);
    }
}
