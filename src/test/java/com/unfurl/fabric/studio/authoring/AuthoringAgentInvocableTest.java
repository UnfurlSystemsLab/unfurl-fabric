package com.unfurl.fabric.studio.authoring;

import com.unfurl.fabric.studio.StudioAuthoringConverseRequest;
import com.unfurl.fabric.studio.StudioAuthoringConverseResponse;
import com.unfurl.fabric.studio.StudioCatalogService;
import com.unfurl.foundry.substrate.agent.AgentDefinition;
import com.unfurl.foundry.substrate.agent.AgentPhase;
import com.unfurl.foundry.substrate.agent.BudgetPolicy;
import com.unfurl.foundry.substrate.model.Message;
import com.unfurl.foundry.substrate.model.ModelRequest;
import com.unfurl.foundry.substrate.model.ModelResponse;
import com.unfurl.foundry.substrate.model.ModelToolCall;
import com.unfurl.foundry.substrate.ports.ModelProvider;
import com.unfurl.foundry.substrate.ports.ToolCallResult;
import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end of the assembly slice: a real {@code EmbeddedAgentRuntime} runs the authoring DAG over
 * a deployment tool and a (scripted) customer model; the terminal phase's structured output becomes
 * the DCP result; and Fabric's Studio endpoint routes it through the generic invocable seam.
 * No foundry product code, no model SDK in Fabric.
 */
class AuthoringAgentInvocableTest {
    private static final String PROPOSAL_JSON = """
            {"kind":"proposal","assistantMessage":"Proposed.","proposal":{
              "needsYaml":"targetApplicationName: bot\\n",
              "intents":[{"type":"ADD_COMPONENT","catalogEntryId":"com.unfurl:validation-service:1.1.0"}],
              "deploymentPolicy":{}}}""";

    @Test
    void runsDagOverToolAndModelThenReturnsGroundedProposal() {
        AuthoringAgentInvocable invocable = invocable();

        ContractInvocation invocation = new ContractInvocation(
                "urn:unfurl:fabric:authoring", "agent.run", "unfurl-fabric", "unfurl-foundry",
                Map.of("userMessage", "a chatbot that answers questions", "catalog", List.of()),
                "corr-1", Map.of(), null, Map.of());

        ContractInvocationResult result = invocable.invoke(invocation, ExecutionContext.empty());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("kind", "proposal");
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) result.output().get("proposal");
        assertThat(String.valueOf(proposal.get("needsYaml"))).contains("bot");
    }

    @Test
    void fabricStudioEndpointRoutesTheDagRunThroughTheInjectedSeam() {
        StudioCatalogService service = new StudioCatalogService().useAuthoringInvocable(invocable());

        StudioAuthoringConverseResponse response = service.converseAuthoring(
                new StudioAuthoringConverseRequest("tenant-local", "assembly-demo", "s", List.of(),
                        "a chatbot that answers questions from our docs"));

        assertThat(response.kind()).isEqualTo("proposal");
        assertThat(response.proposal().needsYaml()).contains("bot");
    }

    private static AuthoringAgentInvocable invocable() {
        AgentPhase phase = new AgentPhase("compose", null, "customer-configured",
                List.of("fabric.needs-emitter"), null, Map.of(), Map.of(), List.of(), 1, List.of());
        AgentDefinition agent = new AgentDefinition("fabric-authoring", "1.0.0", Map.of(),
                List.of(phase), List.of(), Map.of(), "customer-configured", List.of(), BudgetPolicy.none(), List.of());

        // Customer model: first turn calls the needs-emitter tool, second turn emits the proposal JSON.
        ModelProvider model = new SequencedModel(List.of(
                new ModelResponse(Message.assistant(""),
                        List.of(new ModelToolCall("t1", "fabric.needs-emitter",
                                Map.of("capability", "chat.respond", "targetName", "bot"))),
                        "tool_calls", null, Map.of()),
                new ModelResponse(Message.assistant(PROPOSAL_JSON), List.of(), "stop", null, Map.of())));

        ToolExecutor needsEmitter = (request, context) ->
                ToolCallResult.success(Map.of("needsYaml", "targetApplicationName: bot\n"));

        return AuthoringAgentInvocable.create("urn:unfurl:fabric:authoring", "1.0.0", agent, model,
                Map.of("fabric.needs-emitter", needsEmitter));
    }

    private static final class SequencedModel implements ModelProvider {
        private final List<ModelResponse> responses;
        private int index;

        private SequencedModel(List<ModelResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ModelResponse complete(ModelRequest request, ExecutionContext context) {
            return responses.get(Math.min(index++, responses.size() - 1));
        }
    }
}
