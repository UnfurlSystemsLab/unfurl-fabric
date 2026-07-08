package com.unfurl.fabric.workflow;

import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAnalyzerTest {
    @Test
    void extractsDistinctNodeUsesAsRequiredCapabilities(@TempDir Path dir) throws Exception {
        Path workflow = dir.resolve("workflow.yaml");
        Files.writeString(workflow, """
                id: wf
                version: 1.0.0
                nodes:
                  - id: store
                    uses: storage.put
                  - id: audit
                    uses: audit.write
                  - id: store-again
                    uses: storage.put
                """);

        Need need = new WorkflowAnalyzer().analyze(workflow);

        assertThat(need.requiredCapabilities())
                .extracting(CapabilityRequirement::capability)
                .containsExactly("audit.write", "storage.put");
        assertThat(need.requiredCapabilities())
                .extracting(req -> req.capabilityVersion().range())
                .containsExactly("*", "*");
    }

    @Test
    void extractsDistinctNodeUsesFromInlineContent() {
        Need need = new WorkflowAnalyzer().analyzeContent("""
                id: wf
                version: 1.0.0
                nodes:
                  - id: agent
                    uses: agent.run
                  - id: store
                    uses: storage.put
                """, "workflow.yaml");

        assertThat(need.requiredCapabilities())
                .extracting(CapabilityRequirement::capability)
                .containsExactly("agent.run", "storage.put");
    }
}
