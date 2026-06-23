package com.unfurl.fabric.authoring.tools.sample;

import com.unfurl.fabric.studio.authoring.AuthoringTool;
import com.unfurl.foundry.substrate.ports.ToolCallRequest;
import com.unfurl.foundry.substrate.ports.ToolCallResult;
import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code fabric.intent-emitter}: emits the Studio intents that realize a proposal against a resolved
 * catalog entry. Today it emits an {@code ADD_COMPONENT} intent for the chosen entry; richer wiring
 * (ConnectPorts) is layered as the agent matures. Fabric re-validates every emitted intent against
 * the admitted catalog, so an invented {@code catalogEntryId} becomes a gap.
 *
 * <p>Arguments: {@code catalogEntryId} (String). Returns {@code {intents:[{type,catalogEntryId}]}}.
 */
public final class IntentEmitterTool implements AuthoringTool {
    @Override
    public String name() {
        return "fabric.intent-emitter";
    }

    @Override
    public ToolExecutor executor() {
        return IntentEmitterTool::run;
    }

    private static ToolCallResult run(ToolCallRequest request, ExecutionContext context) {
        Object entryId = request.arguments().get("catalogEntryId");
        if (!(entryId instanceof String id) || id.isBlank()) {
            return ToolCallResult.failure("INVALID_ARGUMENT", "catalogEntryId is required");
        }
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("type", "ADD_COMPONENT");
        add.put("catalogEntryId", id);
        return ToolCallResult.success(Map.of("intents", List.of(add)));
    }
}
