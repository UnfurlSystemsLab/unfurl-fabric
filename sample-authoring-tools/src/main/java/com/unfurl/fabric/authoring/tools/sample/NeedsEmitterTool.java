package com.unfurl.fabric.authoring.tools.sample;

import com.unfurl.fabric.studio.authoring.AuthoringTool;
import com.unfurl.foundry.substrate.ports.ToolCallRequest;
import com.unfurl.foundry.substrate.ports.ToolCallResult;
import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.Map;

/**
 * {@code fabric.needs-emitter}: emits the deterministic needs spec (the {@code suggestedNeedsYaml}
 * shape) for a chosen target name + capability. The model decides what is needed; this tool emits
 * the precise, well-formed YAML so the shape never depends on free model text.
 *
 * <p>Arguments: {@code capability} (String), {@code targetName} (String). Returns {@code {needsYaml}}.
 */
public final class NeedsEmitterTool implements AuthoringTool {
    @Override
    public String name() {
        return "fabric.needs-emitter";
    }

    @Override
    public ToolExecutor executor() {
        return NeedsEmitterTool::run;
    }

    private static ToolCallResult run(ToolCallRequest request, ExecutionContext context) {
        Object capability = request.arguments().get("capability");
        if (!(capability instanceof String cap) || cap.isBlank()) {
            return ToolCallResult.failure("INVALID_ARGUMENT", "capability is required");
        }
        Object target = request.arguments().get("targetName");
        String safeName = target instanceof String name && !name.isBlank() ? name.trim() : "authored-app";
        String needsYaml = """
                targetApplicationName: %s
                needs:
                  - capability: %s
                    required: true
                """.formatted(safeName, cap.trim());
        return ToolCallResult.success(Map.of("needsYaml", needsYaml));
    }
}
