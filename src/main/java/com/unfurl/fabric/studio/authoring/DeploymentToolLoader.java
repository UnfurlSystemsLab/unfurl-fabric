package com.unfurl.fabric.studio.authoring;

import com.unfurl.foundry.substrate.ports.ToolExecutor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Loads client-supplied authoring tools from a deployment folder by reflection.
 *
 * <p>The client drops self-contained tool JARs into {@code <deploymentRoot>/tools/}. Each JAR
 * declares its {@link AuthoringTool} implementations via standard Java SPI
 * ({@code META-INF/services/com.unfurl.fabric.studio.authoring.AuthoringTool}). This loader scans
 * the folder, builds an isolated {@link URLClassLoader} over the JARs, and uses
 * {@link ServiceLoader} to discover and instantiate the tools — Fabric never compiles against the
 * tool implementations. The result is a name → {@link ToolExecutor} map the authoring agent DAG
 * runs over.
 */
public final class DeploymentToolLoader {
    private final Path toolsDir;

    /** @param deploymentRoot the deployment folder; tools are read from its {@code tools/} subfolder. */
    public DeploymentToolLoader(Path deploymentRoot) {
        this.toolsDir = deploymentRoot.resolve("tools");
    }

    /**
     * Discover all authoring tools in the deployment {@code tools/} folder. The returned holder owns
     * the JARs' class loader and must be {@link LoadedAuthoringTools#close() closed} on shutdown.
     */
    public LoadedAuthoringTools load() {
        Map<String, ToolExecutor> tools = new LinkedHashMap<>();
        if (!Files.isDirectory(toolsDir)) {
            return new LoadedAuthoringTools(null, tools);
        }
        URL[] jars = jarUrls();
        if (jars.length == 0) {
            return new LoadedAuthoringTools(null, tools);
        }
        // Parent is this loader so the AuthoringTool / ToolExecutor SPI types resolve; the tool
        // implementations come only from the deployment JARs.
        URLClassLoader classLoader = new URLClassLoader(jars, getClass().getClassLoader());
        for (AuthoringTool tool : ServiceLoader.load(AuthoringTool.class, classLoader)) {
            if (tool.name() != null && !tool.name().isBlank()) {
                tools.put(tool.name(), tool.executor());
            }
        }
        return new LoadedAuthoringTools(classLoader, tools);
    }

    private URL[] jarUrls() {
        try (Stream<Path> entries = Files.list(toolsDir)) {
            List<URL> urls = new ArrayList<>();
            entries.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(path -> urls.add(toUrl(path)));
            return urls.toArray(URL[]::new);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to scan authoring tools directory: " + toolsDir, ex);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
