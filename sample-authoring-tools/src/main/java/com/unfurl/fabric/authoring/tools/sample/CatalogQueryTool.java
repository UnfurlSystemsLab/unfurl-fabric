package com.unfurl.fabric.authoring.tools.sample;

import com.unfurl.fabric.studio.authoring.AuthoringTool;
import com.unfurl.foundry.substrate.ports.ToolCallRequest;
import com.unfurl.foundry.substrate.ports.ToolCallResult;
import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * {@code fabric.catalog-query}: resolves an admitted catalog entry offering a requested capability.
 *
 * <p>Grounding primitive — the agent calls this to confirm a catalog component exists before
 * proposing. The catalog is passed in the tool arguments (the agent threads it from its input), so
 * the tool is stateless and depends on nothing but the SPI + the neutral ToolExecutor port.
 *
 * <p>Arguments: {@code capability} (String), {@code catalog} (List of
 * {@code {catalogEntryId, offeredCapabilities[]}}). Returns {@code {found, catalogEntryId}}.
 */
public final class CatalogQueryTool implements AuthoringTool {
    @Override
    public String name() {
        return "fabric.catalog-query";
    }

    @Override
    public ToolExecutor executor() {
        return CatalogQueryTool::run;
    }

    private static ToolCallResult run(ToolCallRequest request, ExecutionContext context) {
        Object capability = request.arguments().get("capability");
        if (!(capability instanceof String cap) || cap.isBlank()) {
            return ToolCallResult.failure("INVALID_ARGUMENT", "capability is required");
        }
        Object catalog = request.arguments().get("catalog");
        if (catalog instanceof List<?> entries) {
            for (Object item : entries) {
                if (item instanceof Map<?, ?> entry && offers(entry, cap)) {
                    return ToolCallResult.success(Map.of(
                            "found", true,
                            "catalogEntryId", String.valueOf(entry.get("catalogEntryId"))));
                }
            }
        }
        return ToolCallResult.success(Map.of("found", false));
    }

    private static boolean offers(Map<?, ?> entry, String capability) {
        return entry.get("offeredCapabilities") instanceof List<?> caps && caps.contains(capability);
    }
}
