package com.unfurl.fabric.studio.authoring;

import com.unfurl.foundry.substrate.ports.ToolCallRequest;
import com.unfurl.foundry.substrate.ports.ToolCallResult;
import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Fabric loads client-supplied tools from {@code deployment/tools/*.jar} by reflection.
 * A sample {@link AuthoringTool} is compiled to a self-contained JAR at test time (it is NOT on
 * Fabric's classpath), dropped into the tools folder, and discovered + run via the loader.
 */
class DeploymentToolLoaderTest {
    @Test
    void discoversAndRunsToolFromExternalJar(@TempDir Path deploymentRoot) throws Exception {
        Path toolsDir = Files.createDirectories(deploymentRoot.resolve("tools"));
        buildSampleToolJar(toolsDir.resolve("echo-tool.jar"));

        try (LoadedAuthoringTools loaded = new DeploymentToolLoader(deploymentRoot).load()) {
            assertThat(loaded.tools()).containsKey("sample.echo");
            ToolCallResult result = loaded.get("sample.echo").orElseThrow()
                    .execute(new ToolCallRequest("c1", "sample.echo", Map.of("in", "hi"), Map.of()),
                            ExecutionContext.empty());
            assertThat(result.success()).isTrue();
            assertThat(result.output()).containsEntry("echo", "hi");
        }
    }

    @Test
    void emptyOrMissingToolsFolderLoadsNothing(@TempDir Path deploymentRoot) throws Exception {
        // No tools/ subfolder at all.
        try (LoadedAuthoringTools loaded = new DeploymentToolLoader(deploymentRoot).load()) {
            assertThat(loaded.isEmpty()).isTrue();
        }
    }

    private static void buildSampleToolJar(Path jarPath) throws IOException {
        Path work = Files.createTempDirectory("authoring-tool-src");
        Path src = work.resolve("sample/EchoTool.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package sample;
                import com.unfurl.fabric.studio.authoring.AuthoringTool;
                import com.unfurl.foundry.substrate.ports.ToolExecutor;
                import com.unfurl.foundry.substrate.ports.ToolCallResult;
                import java.util.Map;
                public class EchoTool implements AuthoringTool {
                    public String name() { return "sample.echo"; }
                    public ToolExecutor executor() {
                        return (request, context) ->
                                ToolCallResult.success(Map.of("echo", request.arguments().get("in")));
                    }
                }
                """);

        Path classes = Files.createDirectories(work.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, System.err,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                src.toString());
        if (rc != 0) {
            throw new IllegalStateException("failed to compile sample tool");
        }

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // the compiled tool class
            Path clazz = classes.resolve("sample/EchoTool.class");
            jar.putNextEntry(new JarEntry("sample/EchoTool.class"));
            jar.write(Files.readAllBytes(clazz));
            jar.closeEntry();
            // the SPI service declaration
            jar.putNextEntry(new JarEntry("META-INF/services/com.unfurl.fabric.studio.authoring.AuthoringTool"));
            jar.write("sample.EchoTool\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
