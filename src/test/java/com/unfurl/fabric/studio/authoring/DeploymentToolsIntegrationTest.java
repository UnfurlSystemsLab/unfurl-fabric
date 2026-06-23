package com.unfurl.fabric.studio.authoring;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end against the <em>real</em> reference tool JAR: when the sample tools JAR has been built
 * and dropped into {@code deployment/tools/}, the loader discovers the three authoring tools by
 * reflection. Skipped if the JAR is absent (e.g. a clean checkout before
 * {@code sample-authoring-tools} is packaged), so it never fails spuriously.
 */
class DeploymentToolsIntegrationTest {
    @Test
    void loadsTheThreeReferenceToolsFromTheDeploymentJar() throws Exception {
        Path deployment = Path.of("deployment");
        assumeTrue(hasJar(deployment.resolve("tools")), "no built tool JAR in deployment/tools/");

        try (LoadedAuthoringTools loaded = new DeploymentToolLoader(deployment).load()) {
            assertThat(loaded.tools().keySet())
                    .contains("fabric.catalog-query", "fabric.needs-emitter", "fabric.intent-emitter");
        }
    }

    private static boolean hasJar(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.anyMatch(p -> p.getFileName().toString().endsWith(".jar"));
        }
    }
}
