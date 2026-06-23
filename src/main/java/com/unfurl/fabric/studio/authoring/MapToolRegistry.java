package com.unfurl.fabric.studio.authoring;

import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.foundry.substrate.ports.ToolRegistry;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.Map;
import java.util.Optional;

/**
 * A {@link ToolRegistry} backed by the tools loaded from the deployment {@code tools/} folder
 * (see {@link DeploymentToolLoader}). Resolves a {@link ToolExecutor} by the name the
 * {@link AuthoringTool} declared, which the agent DAG references in its {@code allowedToolRefs}.
 */
public final class MapToolRegistry implements ToolRegistry {
    private final Map<String, ToolExecutor> tools;

    public MapToolRegistry(Map<String, ToolExecutor> tools) {
        this.tools = Map.copyOf(tools);
    }

    @Override
    public boolean hasTool(String toolName, ExecutionContext context) {
        return tools.containsKey(toolName);
    }

    @Override
    public Optional<ToolExecutor> resolveTool(String toolName, ExecutionContext context) {
        return Optional.ofNullable(tools.get(toolName));
    }
}
