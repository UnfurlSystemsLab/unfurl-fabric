package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
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

    /**
     * Verifies stale or failing Foundry endpoints still provide actionable sanitized diagnostics.
     */
    @Test
    void includesSanitizedFoundryErrorBodyWhenEndpointReturnsTransportFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/dcp/agent.run", exchange -> {
                byte[] bytes = mapper.writeValueAsBytes(Map.of("error",
                        "Failed to generate content: models/gemini-3.1-flash-lite missing apiKey="
                                + fakeProviderApiKey()));
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(500, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            DcpAuthoringClient client = new DcpAuthoringClient(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/dcp/agent.run"));
            ContractInvocationResult result = client.invoke(new ContractInvocation(
                    "urn:unfurl:fabric:authoring", "agent.run", "fabric", "foundry",
                    Map.of("tenantId", "tenant-a"), "corr", Map.of(), null, Map.of()), ExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("DCP_TRANSPORT_ERROR");
            assertThat(result.errorMessage())
                    .contains("HTTP 500")
                    .contains("Failed to generate content")
                    .contains("models/gemini-3.1-flash-lite")
                    .contains("<redacted>")
                    .doesNotContain("AIza");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Regression test: a slow or body-stalled Foundry endpoint must release the Studio
     * authoring request with a structured timeout instead of leaving the browser spinner active.
     */
    @Test
    void timesOutSlowFoundryDcpEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/dcp/agent.run", exchange -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                byte[] bytes = mapper.writeValueAsBytes(ContractInvocationResult.success(Map.of("kind", "gap")));
                exchange.getResponseHeaders().set("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            DcpAuthoringClient client = new DcpAuthoringClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/dcp/agent.run"),
                    java.net.http.HttpClient.newHttpClient(),
                    mapper,
                    Duration.ofMillis(25));
            ContractInvocationResult result = client.invoke(new ContractInvocation(
                    "urn:unfurl:fabric:authoring", "agent.run", "fabric", "foundry",
                    Map.of("tenantId", "tenant-a"), "corr", Map.of(), null, Map.of()), ExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("DCP_TRANSPORT_TIMEOUT");
            assertThat(result.errorMessage()).contains("25 ms");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Test fixture helper: creates a provider-shaped credential at runtime so
     * redaction coverage does not require committing a key-shaped literal.
     */
    private static String fakeProviderApiKey() {
        return String.join("", "AI", "za", "Sy", "Fake", "Secret", "Value", "1234567890");
    }
}
