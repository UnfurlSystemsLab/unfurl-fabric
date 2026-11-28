package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioCatalogService;
import com.unfurl.fabric.studio.StudioDeploymentResolveRequest;
import com.unfurl.fabric.studio.StudioDeploymentService;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP Adapter (Ports & Adapters) for {@code POST /studio/deployment/resolve}: reads a
 * {@link StudioDeploymentResolveRequest} and hands it to the session-backed resolver ({@link StudioCatalogService}) or
 * the filesystem resolver ({@link StudioDeploymentService}), whichever strategy the request carries. A request Fabric
 * cannot act on is a {@code 400} with its reason, never a guess at the missing inputs.
 */
public final class ResolveDeploymentHandler {
    private final StudioDeploymentService service;
    private final StudioCatalogService catalogService;
    private final ObjectMapper mapper;

    /**
     * Backward-compatible constructor: keeps existing tests and embedded server callers on
     * the filesystem resolver path unless a catalog service is explicitly supplied.
     */
    public ResolveDeploymentHandler(StudioDeploymentService service, ObjectMapper mapper) {
        this(service, null, mapper);
    }

    /**
     * Adapter constructor: accepts both the stateless deployment resolver and the tenant
     * catalog/session facade so one HTTP route can support debug and UI session modes.
     */
    public ResolveDeploymentHandler(
            StudioDeploymentService service,
            StudioCatalogService catalogService,
            ObjectMapper mapper
    ) {
        this.service = service == null ? new StudioDeploymentService() : service;
        this.catalogService = catalogService == null ? new StudioCatalogService() : catalogService;
        this.mapper = mapper;
    }

    /**
     * HTTP adapter: deserializes a deployment resolve request and dispatches to the
     * session-backed or filesystem-backed resolver according to the request shape.
     *
     * <p>The request checks its own inputs while the body is read, and Jackson wraps that failure as a
     * {@link JsonProcessingException}. Its message is the one answered ({@code DEPLOYMENT_RESOLVE_INPUTS_INVALID} names
     * what is missing); only a body that is not JSON, or does not fit the request, is "malformed json body"
     * (LLD-fabric-studio-api, Deployment Resolution).
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, Map.of("error", "method not allowed: " + exchange.getRequestMethod()));
            return;
        }
        try {
            StudioDeploymentResolveRequest request = mapper.readValue(
                    exchange.getRequestBody(),
                    StudioDeploymentResolveRequest.class);
            write(exchange, 200, request.usesSessionState()
                    ? catalogService.resolveDeployment(request)
                    : service.resolveDeployment(request));
        } catch (JsonProcessingException ex) {
            IllegalArgumentException invalid = requestValidation(ex);
            write(exchange, 400, Map.of("error", invalid == null ? "malformed json body" : invalid.getMessage()));
        } catch (IllegalArgumentException ex) {
            write(exchange, 400, Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            write(exchange, 500, Map.of("error", ex.getMessage()));
        }
    }

    /**
     * The request's own validation failure inside a read failure, if that is what it was: the first
     * {@link IllegalArgumentException} in the cause chain.
     *
     * @return that exception, or {@code null} when the body itself was malformed.
     */
    private static IllegalArgumentException requestValidation(JsonProcessingException ex) {
        for (Throwable cause = ex.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException invalid) {
                return invalid;
            }
        }
        return null;
    }

    /**
     * Response writer: serializes a JSON body and closes the exchange.
     */
    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
