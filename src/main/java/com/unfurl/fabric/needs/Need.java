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
 *
 * <p>Pattern: immutable <b>aggregate value object</b> with a normalizing compact constructor and a
 * convenience factory.
 *
 * @param requiredCapabilities capabilities the composition must satisfy.
 * @param optionalCapabilities capabilities preferred but not mandatory.
 * @param artifactConstraints  operator pins to specific artifacts/versions.
 * @param refusalExpectations  concerns the operator expects components to refuse to own.
 * @param trustPolicyRef       optional path to a trust policy document.
 * @param bindingPreferences   preferred binding mode per capability.
 */
public record Need(
        List<CapabilityRequirement> requiredCapabilities,
        List<CapabilityRequirement> optionalCapabilities,
        List<ArtifactConstraint> artifactConstraints,
        Set<String> refusalExpectations,
        Path trustPolicyRef,
        Map<String, BindingMode> bindingPreferences
) {
    /** Compact constructor: defensively copies every collection/map field (null → empty). */
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

    /**
     * Convenience factory for a need consisting only of required capabilities (no optionals,
     * constraints, refusals, trust policy, or binding preferences).
     *
     * @param required the required capability requirements.
     * @return the assembled need.
     */
    public static Need ofRequiredCapabilities(CapabilityRequirement... required) {
        return new Need(List.of(required), List.of(), List.of(), Set.of(), null, Map.of());
    }
}
