package com.unfurl.fabric.studio;

import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Fabric's authoring endpoint routes over the neutral DCP {@link ContractInvocable}
 * seam when one is injected (the foundry agent over {@code agent.run}), and falls back to the
 * deterministic bridge when none is. Fabric depends only on the neutral composition types.
 */
class StudioCatalogServiceAuthoringDcpTest {
    private static final StudioAuthoringConverseRequest REQUEST = new StudioAuthoringConverseRequest(
            "tenant-local", "assembly-demo", "session-1", List.of(), "a chatbot that answers questions from our docs");

    @Test
    void routesProposalThroughInjectedDcpInvocable() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "proposal",
                        "assistantMessage", "Proposed by the agent.",
                        "proposal", Map.of(
                                "needsYaml", "targetApplicationName: bot\n",
                                "intents", List.of(Map.of("type", "ADD_COMPONENT",
                                        "catalogEntryId", "com.unfurl:validation-service:1.1.0")),
                                "deploymentPolicy", Map.of()))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("proposal");
        assertThat(response.assistantMessage()).isEqualTo("Proposed by the agent.");
        assertThat(response.proposal().needsYaml()).contains("bot");
        assertThat(response.proposal().intents()).hasSize(1);
    }

    @Test
    void rejectsProposalWhoseComponentIsNotAdmittedInTheCatalog() {
        // The agent proposes a component the tenant's admitted catalog does not contain.
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "proposal",
                        "assistantMessage", "Proposed.",
                        "proposal", Map.of(
                                "needsYaml", "targetApplicationName: x\n",
                                "intents", List.of(Map.of("type", "ADD_COMPONENT", "catalogEntryId", "not-admitted")),
                                "deploymentPolicy", Map.of()))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.unmet()).containsExactly("not-admitted");
    }

    @Test
    void routesGapThroughInjectedDcpInvocable() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "gap",
                        "assistantMessage", "No component for that.",
                        "unmet", List.of("video.transcode"))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.unmet()).containsExactly("video.transcode");
    }

    /**
     * Regression test: preserves a Foundry-backed runbook execution response only
     * when the agent result includes the concrete tool call that produced it.
     */
    @Test
    void routesExecutionThroughInjectedDcpInvocableWhenToolCallIsRecorded() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "execution",
                        "phase", "catalog-creation",
                        "step", 2,
                        "assistantMessage", "Catalog admission completed.",
                        "toolResult", Map.of("status", "PASS"),
                        "toolCalls", List.of(Map.of(
                                "id", "step-02-catalog-admit",
                                "toolName", "fabric.catalog-admit",
                                "status", "PASS")),
                        "artifacts", List.of(Map.of(
                                "path", "unfurl-fabric/target/flowfoundry-run/step-02-catalog-admission-response.json",
                                "sha256", "sha256:abc",
                                "consumedByStep", 3)))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("execution");
        assertThat(response.phase()).isEqualTo("catalog-creation");
        assertThat(response.step()).isEqualTo(2);
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst()).containsEntry("toolName", "fabric.catalog-admit");
        assertThat(response.artifacts()).hasSize(1);
    }

    /**
     * Regression test: blocks prompt-only execution claims so the UI cannot advance
     * Step 2 without evidence that Foundry actually called the Studio tool.
     */
    @Test
    void rejectsExecutionWithoutRecordedToolCall() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "execution",
                        "phase", "catalog-creation",
                        "step", 2,
                        "assistantMessage", "Catalog admission completed.",
                        "toolResult", Map.of("status", "PASS"))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.assistantMessage()).contains("without a recorded tool call");
        assertThat(response.unmet()).containsExactly("toolCalls");
    }

    /**
     * Regression test: converts a tool-level GAP into the same authoring gap shape
     * used for catalog and composition blockers.
     */
    @Test
    void routesExecutionToolGapThroughGapResponse() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "execution",
                        "phase", "catalog-creation",
                        "step", 2,
                        "assistantMessage", "Catalog admission did not pass.",
                        "toolResult", Map.of(
                                "status", "GAP",
                                "diagnostics", List.of("catalog artifact is invalid")),
                        "toolCalls", List.of(Map.of(
                                "id", "step-02-catalog-admit",
                                "toolName", "fabric.catalog-admit",
                                "status", "GAP")))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.unmet()).containsExactly("catalog artifact is invalid");
    }

    @Test
    void withoutInvocableFallsBackToDeterministicBridge() {
        // No invocable injected: a short prompt still produces the deterministic clarify response.
        StudioCatalogService service = new StudioCatalogService();

        StudioAuthoringConverseResponse response = service.converseAuthoring(
                new StudioAuthoringConverseRequest("tenant-local", "assembly-demo", "s", List.of(), "app"));

        assertThat(response.kind()).isEqualTo("clarify");
        assertThat(response.questions()).isNotEmpty();
    }

    private static ContractInvocable invocable(Map<String, Object> output) {
        return new ContractInvocable() {
            @Override
            public String contractId() {
                return "urn:unfurl:fabric:authoring";
            }

            @Override
            public String contractVersion() {
                return "1.0.0";
            }

            @Override
            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                return new ContractInvocationResult(true, output, null, null, Map.of());
            }
        };
    }
}
