package com.unfurl.fabric.studio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data Transfer Object / Anti-Corruption Layer: mirrors Foundry's ToolCallResult
 * JSON so Foundry HTTP tool bindings can consume Fabric Studio tool responses
 * without a Fabric dependency on Foundry substrate classes.
 */
public record StudioToolCallResult(
        boolean success,
        Map<String, Object> output,
        String errorCode,
        String errorMessage
) {
    /**
     * Invariant constructor: freezes output while preserving optional null JSON
     * values and normalizes absent error text.
     */
    public StudioToolCallResult {
        output = immutable(output);
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * Factory: creates a successful tool result whose output carries the
     * runbook-level PASS/GAP status.
     */
    public static StudioToolCallResult success(Map<String, Object> output) {
        return new StudioToolCallResult(true, output, "", "");
    }

    /**
     * Factory: creates a failed tool result for unexpected execution failures
     * that Foundry should treat as tool errors rather than business gaps.
     */
    public static StudioToolCallResult failure(String code, String message) {
        return new StudioToolCallResult(false, Map.of(), code, message);
    }

    /**
     * Map freezer: creates an unmodifiable linked map without rejecting null JSON
     * values emitted by existing Studio DTOs.
     */
    private static Map<String, Object> immutable(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
