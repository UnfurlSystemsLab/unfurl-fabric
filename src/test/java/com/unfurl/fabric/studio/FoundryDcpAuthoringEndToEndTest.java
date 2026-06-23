package com.unfurl.fabric.studio;

import com.unfurl.foundry.providers.InMemoryProviderRegistry;
import com.unfurl.foundry.registries.InMemoryToolRegistry;
import com.unfurl.foundry.runtime.AgentRunInvocable;
import com.unfurl.foundry.runtime.FoundryDcpServer;
import com.unfurl.foundry.substrate.agent.AgentDefinition;
import com.unfurl.foundry.substrate.agent.AgentPhase;
import com.unfurl.foundry.substrate.engine.EmbeddedAgentRuntime;
import com.unfurl.foundry.substrate.model.Message;
import com.unfurl.foundry.substrate.model.ModelResponse;
import com.unfurl.foundry.substrate.model.ModelUsage;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FoundryDcpAuthoringEndToEndTest {
    @Test
    void fabricDcpClientDrivesFoundryAgentRunWithScriptedModel() throws Exception {
        InMemoryProviderRegistry providers = new InMemoryProviderRegistry()
                .registerModel("customer-configured", (request, context) -> new ModelResponse(
                        Message.assistant("""
                                {"kind":"proposal","assistantMessage":"scripted","proposal":{
                                  "needsYaml":"targetApplicationName: bot\\n",
                                  "intents":[{"type":"ADD_COMPONENT","catalogEntryId":"com.unfurl:validation-service:1.1.0"}],
                                  "deploymentPolicy":{}}}
                                """),
                        List.of(),
                        "stop",
                        ModelUsage.zero(),
                        Map.of("providerName", "scripted")));
        EmbeddedAgentRuntime runtime = new EmbeddedAgentRuntime(providers, new InMemoryToolRegistry());
        AgentDefinition agent = new AgentDefinition(
                "fabric-authoring",
                "1.0.0",
                Map.of(),
                List.of(new AgentPhase(
                        "terminal",
                        null,
                        "customer-configured",
                        List.of(),
                        null,
                        Map.of("prompt", "$.agent.input.userMessage"),
                        Map.of(),
                        List.of(),
                        0)),
                List.of(),
                Map.of(),
                "customer-configured",
                List.of());

        FoundryDcpServer server = new FoundryDcpServer(
                "127.0.0.1",
                0,
                new AgentRunInvocable("urn:unfurl:fabric:authoring", "1.0.0", agent, runtime));
        try {
            server.start();
            DcpAuthoringClient client = new DcpAuthoringClient(
                    URI.create("http://127.0.0.1:" + server.port() + FoundryDcpServer.INVOKE_PATH));
            StudioCatalogService service = new StudioCatalogService().useAuthoringInvocable(client);

            StudioAuthoringConverseResponse response = service.converseAuthoring(new StudioAuthoringConverseRequest(
                    "tenant-local",
                    "assembly-demo",
                    "session-1",
                    List.of(),
                    "a chatbot that answers questions from docs"));

            assertThat(response.kind()).isEqualTo("proposal");
            assertThat(response.assistantMessage()).isEqualTo("scripted");
            assertThat(response.proposal().needsYaml()).contains("bot");
            assertThat(response.proposal().intents()).containsExactly(Map.of(
                    "type", "ADD_COMPONENT",
                    "catalogEntryId", "com.unfurl:validation-service:1.1.0"));
        } finally {
            server.close();
        }
    }
}
