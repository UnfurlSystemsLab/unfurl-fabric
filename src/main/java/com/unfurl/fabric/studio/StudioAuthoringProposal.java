package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;

public record StudioAuthoringProposal(
        String needsYaml,
        List<Map<String, Object>> intents,
        StudioDeploymentPolicyDraft deploymentPolicy,
        List<String> warnings
) {
    public StudioAuthoringProposal {
        needsYaml = needsYaml == null ? "" : needsYaml;
        intents = intents == null ? List.of() : List.copyOf(intents);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
