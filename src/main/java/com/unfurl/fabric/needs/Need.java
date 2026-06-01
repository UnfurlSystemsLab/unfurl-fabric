package com.unfurl.fabric.needs;

import com.unfurl.substrate.api.BindingMode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator-authored input describing what a deployment needs. Phase B's primary input shape;
 * workflow analysis (in Phase E) emits a suggested Need that the operator reviews before
 * compiling.
 */
public record Need(
        List<CapabilityRequirement> requiredCapabilities,
        List<CapabilityRequirement> optionalCapabilities,
        List<ArtifactConstraint> artifactConstraints,
        Set<String> refusalExpectations,
        Path trustPolicyRef,
        Map<String, BindingMode> bindingPreferences
) {
    public Need {
        requiredCapabilities = requiredCapabilities == null
                ? List.of() : List.copyOf(requiredCapabilities);
        optionalCapabilities = optionalCapabilities == null
                ? List.of() : List.copyOf(optionalCapabilities);
        artifactConstraints = artifactConstraints == null
                ? List.of() : List.copyOf(artifactConstraints);
        refusalExpectations = refusalExpectations == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(refusalExpectations));
        bindingPreferences = bindingPreferences == null
                ? Map.of() : Map.copyOf(new HashMap<>(bindingPreferences));
    }

    public static Need ofRequiredCapabilities(CapabilityRequirement... required) {
        return new Need(List.of(required), List.of(), List.of(), Set.of(), null, Map.of());
    }
}
