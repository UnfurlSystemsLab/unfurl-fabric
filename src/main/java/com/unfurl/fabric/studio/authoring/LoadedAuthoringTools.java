package com.unfurl.fabric.studio.authoring;

import com.unfurl.foundry.substrate.ports.ToolExecutor;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Optional;

/**
 * The tools discovered from a deployment {@code tools/} folder, together with the isolated
 * class loader that owns their JARs. The class loader must stay open for as long as the tools are
 * used (lazy class loading), so this holder is {@link AutoCloseable}: close it on shutdown to
 * release the JAR files.
 */
public final class LoadedAuthoringTools implements AutoCloseable {
    private final URLClassLoader classLoader;
    private final Map<String, ToolExecutor> tools;

    LoadedAuthoringTools(URLClassLoader classLoader, Map<String, ToolExecutor> tools) {
        this.classLoader = classLoader;
        this.tools = Map.copyOf(tools);
    }

    /** Discovered tools keyed by {@link AuthoringTool#name()}. */
    public Map<String, ToolExecutor> tools() {
        return tools;
    }

    public Optional<ToolExecutor> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    @Override
    public void close() throws IOException {
        if (classLoader != null) {
            classLoader.close();
        }
    }
}
