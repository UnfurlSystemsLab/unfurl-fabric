package com.unfurl.fabric.studio.authoring;

import com.unfurl.foundry.substrate.ports.ToolExecutor;

/**
 * SPI a client implements to contribute a tool to the Fabric authoring agent.
 *
 * <p>Tools are <b>not</b> Fabric or foundry source. A client drops a self-contained JAR into the
 * deployment {@code tools/} folder; each JAR declares its tools via
 * {@code META-INF/services/com.unfurl.fabric.studio.authoring.AuthoringTool} (standard Java
 * {@link java.util.ServiceLoader}). Fabric discovers them by reflection ({@code DeploymentToolLoader}),
 * binds them by {@link #name()} into a tool registry, and runs the authoring agent DAG over them.
 *
 * <p>An implementation must have a public no-arg constructor (ServiceLoader requirement) and depend
 * only on this SPI plus the neutral foundry-substrate {@link ToolExecutor} port — never on Fabric
 * or foundry internals.
 */
public interface AuthoringTool {
    /** Logical tool name the agent references (e.g. {@code "fabric.catalog-query"}). */
    String name();

    /** The grounded, SDK-free executor invoked when the agent calls this tool. */
    ToolExecutor executor();
}
