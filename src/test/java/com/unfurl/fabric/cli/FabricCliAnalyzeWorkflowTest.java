package com.unfurl.fabric.cli;

import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.needs.NeedsCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FabricCliAnalyzeWorkflowTest {
    @Test
    void writesSuggestedNeedsFromWorkflowYaml(@TempDir Path dir) throws Exception {
        Path workflow = writeWorkflow(dir);
        Path out = dir.resolve("suggested.needs.yaml");

        CliTestFixtures.CliRun result = CliTestFixtures.run("analyze-workflow",
                "--workflow", workflow.toString(),
                "--out", out.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("suggestedNeeds=")
                .contains("requiredCapabilities=2")
                .contains("- function.local")
                .contains("- storage.put");
        Need need = new NeedsCodec().read(out);
        assertThat(need.requiredCapabilities())
                .extracting(CapabilityRequirement::capability)
                .containsExactly("function.local", "storage.put");
    }

    @Test
    void printsSuggestedNeedsToStdoutWhenOutIsAbsent(@TempDir Path dir) throws Exception {
        Path workflow = writeWorkflow(dir);

        CliTestFixtures.CliRun result = CliTestFixtures.run("analyze-workflow",
                "--workflow", workflow.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
        assertThat(result.stdout())
                .contains("requiredCapabilities:")
                .contains("capability: function.local")
                .contains("capability: storage.put")
                .contains("requiredCapabilities=2");
    }

    private Path writeWorkflow(Path dir) throws Exception {
        Path workflow = dir.resolve("workflow.yaml");
        Files.writeString(workflow, """
                id: analyzed
                version: 1.0.0
                nodes:
                  - id: echo
                    type: ACTION
                    uses: function.local
                  - id: save
                    type: ACTION
                    uses: storage.put
                  - id: blank
                    type: ACTION
                """);
        return workflow;
    }
}
