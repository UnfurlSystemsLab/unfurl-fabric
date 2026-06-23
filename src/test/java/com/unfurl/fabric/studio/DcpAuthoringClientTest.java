package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DcpAuthoringClientTest {
    private final ObjectMapper mapper = StudioJson.mapper();

    @Test
    void postsContractInvocationToDcpEndpointAndReturnsResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/dcp/agent.run", exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), Map.class);
                assertThat(request).containsKeys("invocation", "context");
                byte[] bytes = mapper.writeValueAsBytes(ContractInvocationResult.success(Map.of(
                        "kind", "gap",
                        "assistantMessage", "from foundry")));
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            DcpAuthoringClient client = new DcpAuthoringClient(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/dcp/agent.run"));
            ContractInvocationResult result = client.invoke(new ContractInvocation(
                    "urn:unfurl:fabric:authoring", "agent.run", "fabric", "foundry",
                    Map.of("tenantId", "tenant-a"), "corr", Map.of(), null, Map.of()), ExecutionContext.empty());

            assertThat(result.success()).isTrue();
            assertThat(result.output()).containsEntry("assistantMessage", "from foundry");
        } finally {
            server.stop(0);
        }
    }
}
