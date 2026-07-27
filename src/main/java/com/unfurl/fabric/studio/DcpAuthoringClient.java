package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Map;
import java.util.Optional;

/**
 * DCP-over-HTTP client for Fabric's authoring delegation to Foundry.
 */
public final class DcpAuthoringClient implements ContractInvocable {
    public static final String ENDPOINT_PROPERTY = "unfurl.foundry.dcp.endpoint";
    public static final String ENDPOINT_ENV = "UNFURL_FOUNDRY_DCP_ENDPOINT";
    public static final String TIMEOUT_MILLIS_PROPERTY = "unfurl.foundry.dcp.timeoutMillis";
    public static final String TIMEOUT_MILLIS_ENV = "UNFURL_FOUNDRY_DCP_TIMEOUT_MS";

    private static final String CONTRACT_ID = "urn:unfurl:fabric:authoring";
    private static final String CONTRACT_VERSION = "1.0.0";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public DcpAuthoringClient(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), StudioJson.mapper(),
                configuredTimeout());
    }

    DcpAuthoringClient(URI endpoint, HttpClient client, ObjectMapper mapper) {
        this(endpoint, client, mapper, DEFAULT_TIMEOUT);
    }

    DcpAuthoringClient(URI endpoint, HttpClient client, ObjectMapper mapper, Duration timeout) {
        this.endpoint = endpoint;
        this.client = client;
        this.mapper = mapper;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
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
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("invocation", invocation, "context", context == null ? ExecutionContext.empty() : context)),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = sendWithinTimeout(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ContractInvocationResult.failure("DCP_TRANSPORT_ERROR",
                        transportErrorMessage(response.statusCode(), response.body()));
            }
            return mapper.readValue(response.body(), ContractInvocationResult.class);
        } catch (IOException ex) {
            return ContractInvocationResult.failure("DCP_TRANSPORT_ERROR", ex.getMessage());
        } catch (CompletionException ex) {
            return completionFailure(ex);
        }
    }

    /**
     * Timeout adapter: bounds the complete Foundry DCP exchange, including response body
     * completion, so an opened but stalled HTTP response cannot hold a Studio request forever.
     */
    private HttpResponse<String> sendWithinTimeout(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    /**
     * Failure mapper: converts async HTTP failures into DCP transport result
     * failures while preserving a distinct timeout code for operator action.
     */
    private ContractInvocationResult completionFailure(CompletionException ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
            return ContractInvocationResult.failure(
                    "DCP_TRANSPORT_TIMEOUT",
                    "Foundry DCP endpoint timed out after " + timeout.toMillis() + " ms");
        }
        if (cause instanceof IOException io) {
            return ContractInvocationResult.failure("DCP_TRANSPORT_ERROR", io.getMessage());
        }
        return ContractInvocationResult.failure("DCP_TRANSPORT_ERROR",
                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    /**
     * Configuration reader: accepts an operator-supplied DCP timeout for interactive
     * authoring while keeping a bounded default when no setting is present.
     */
    private static Duration configuredTimeout() {
        String configured = System.getProperty(TIMEOUT_MILLIS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(TIMEOUT_MILLIS_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        try {
            long millis = Long.parseLong(configured.trim());
            return millis <= 0 ? DEFAULT_TIMEOUT : Duration.ofMillis(millis);
        } catch (NumberFormatException ex) {
            return DEFAULT_TIMEOUT;
        }
    }

    /**
     * Diagnostic adapter: includes a sanitized Foundry response body in transport errors so
     * operators can distinguish provider failures from endpoint availability problems.
     */
    private String transportErrorMessage(int statusCode, String body) {
        String detail = responseErrorDetail(body);
        String message = "Foundry DCP endpoint returned HTTP " + statusCode;
        return detail.isBlank() ? message : message + ": " + detail;
    }

    /**
     * Response parser: accepts either a JSON error envelope or plain text and bounds/redacts the
     * diagnostic before it becomes a Studio-visible DCP transport failure.
     */
    @SuppressWarnings("unchecked")
    private String responseErrorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = mapper.readValue(body, Map.class);
            Object error = payload.get("error");
            if (error == null) {
                error = payload.get("errorMessage");
            }
            if (error != null) {
                return sanitizeDiagnostic(String.valueOf(error));
            }
        } catch (IOException ignored) {
            // Fall back to sanitized plain text when the endpoint returns a non-JSON body.
        }
        return sanitizeDiagnostic(body);
    }

    /**
     * Redaction helper: removes common credential shapes from Foundry transport diagnostics.
     */
    private String sanitizeDiagnostic(String value) {
        String redacted = value
                .replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "<redacted>")
                .replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1<redacted>")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/\\-=]+", "$1<redacted>")
                .replaceAll("(?i)((api[_-]?key|apikey|token|secret|password)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1<redacted>");
        return redacted.length() <= 1000 ? redacted : redacted.substring(0, 1000) + "...";
    }
}
