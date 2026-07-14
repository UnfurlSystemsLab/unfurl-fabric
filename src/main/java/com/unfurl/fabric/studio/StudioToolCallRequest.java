package com.unfurl.fabric.studio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data Transfer Object / Anti-Corruption Layer: mirrors Foundry's stable
 * ToolCallRequest JSON without making Fabric depend on Foundry runtime classes.
 * Fabric uses this record only at the HTTP boundary before dispatching to
 * existing Studio service methods.
 */
public record StudioToolCallRequest(
        String callId,
        String toolName,
        Map<String, Object> arguments,
        Map<String, Object> metadata
) {
    /**
     * Invariant constructor: normalizes nullable text and freezes argument maps
     * while allowing JSON null values inside those maps.
     */
    public StudioToolCallRequest {
        callId = callId == null ? "" : callId.trim();
        toolName = toolName == null ? "" : toolName.trim();
        arguments = immutable(arguments);
        metadata = immutable(metadata);
    }

    /**
     * Factory: returns a copy with the route-resolved tool name, preserving the
     * original call id, arguments, and metadata from the Foundry request.
     */
    public StudioToolCallRequest withToolName(String resolvedToolName) {
        return new StudioToolCallRequest(callId, resolvedToolName, arguments, metadata);
    }

    /**
     * Map freezer: creates an unmodifiable linked map without rejecting null JSON
     * values that may appear in optional request fields.
     */
    private static Map<String, Object> immutable(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
