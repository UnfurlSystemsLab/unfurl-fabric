package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * DCP-over-HTTP client for Fabric's authoring delegation to Foundry.
 */
public final class DcpAuthoringClient implements ContractInvocable {
    public static final String ENDPOINT_PROPERTY = "unfurl.foundry.dcp.endpoint";
    public static final String ENDPOINT_ENV = "UNFURL_FOUNDRY_DCP_ENDPOINT";

    private static final String CONTRACT_ID = "urn:unfurl:fabric:authoring";
    private static final String CONTRACT_VERSION = "1.0.0";

    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public DcpAuthoringClient(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), StudioJson.mapper());
    }

    DcpAuthoringClient(URI endpoint, HttpClient client, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.client = client;
        this.mapper = mapper;
    }

    public static Optional<DcpAuthoringClient> fromEnvironment() {
        String configured = System.getProperty(ENDPOINT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENDPOINT_ENV);
        }
        return configured == null || configured.isBlank()
                ? Optional.empty()
                : Optional.of(new DcpAuthoringClient(URI.create(configured)));
    }

    @Override
    public String contractId() {
        return CONTRACT_ID;
    }

    @Override
    public String contractVersion() {
        return CONTRACT_VERSION;
    }

    @Override
    public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMinutes(2))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("invocation", invocation, "context", context == null ? ExecutionContext.empty() : context)),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ContractInvocationResult.failure("DCP_TRANSPORT_ERROR",
                        "Foundry DCP endpoint returned HTTP " + response.statusCode());
            }
            return mapper.readValue(response.body(), ContractInvocationResult.class);
        } catch (IOException ex) {
            return ContractInvocationResult.failure("DCP_TRANSPORT_ERROR", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ContractInvocationResult.failure("DCP_TRANSPORT_INTERRUPTED", ex.getMessage());
        }
    }
}
