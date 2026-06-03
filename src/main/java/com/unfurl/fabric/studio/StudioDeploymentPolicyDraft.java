package com.unfurl.fabric.studio;

import java.util.List;

public record StudioDeploymentPolicyDraft(
        List<String> preferredShapes,
        List<String> disallowedShapes,
        List<String> requireIsolationForCapabilityPatterns,
        StudioDeploymentRuntimeDraft runtime) {

    public StudioDeploymentPolicyDraft {
        preferredShapes = preferredShapes == null ? List.of() : List.copyOf(preferredShapes);
        disallowedShapes = disallowedShapes == null ? List.of() : List.copyOf(disallowedShapes);
        requireIsolationForCapabilityPatterns = requireIsolationForCapabilityPatterns == null
                ? List.of()
                : List.copyOf(requireIsolationForCapabilityPatterns);
        runtime = runtime == null ? new StudioDeploymentRuntimeDraft(null, null, null, null, null) : runtime;
    }
}
