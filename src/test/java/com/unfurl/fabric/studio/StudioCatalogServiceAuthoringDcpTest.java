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
