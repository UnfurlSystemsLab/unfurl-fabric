package com.unfurl.fabric.studio;

import java.util.List;

public record StudioDeploymentResolveResponse(
        String status,
        String candidateId,
        List<StudioDeploymentSelection> selections,
        List<String> warnings,
        String reason,
        String details,
        List<String> rejectedShapes) {

    public StudioDeploymentResolveResponse {
        selections = selections == null ? List.of() : List.copyOf(selections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        rejectedShapes = rejectedShapes == null ? List.of() : List.copyOf(rejectedShapes);
    }

    public static StudioDeploymentResolveResponse resolved(
            String candidateId,
            List<StudioDeploymentSelection> selections,
            List<String> warnings) {
        return new StudioDeploymentResolveResponse(
                "RESOLVED",
                candidateId,
                selections,
                warnings,
                null,
                null,
                List.of());
    }

    public static StudioDeploymentResolveResponse invalid(
            String reason,
            String details,
            List<String> rejectedShapes) {
        return new StudioDeploymentResolveResponse(
                "INVALID",
                null,
                List.of(),
                List.of(),
                reason,
                details,
                rejectedShapes);
    }
}
