package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioCatalogService;
import com.unfurl.fabric.studio.StudioDeploymentResolveRequest;
import com.unfurl.fabric.studio.StudioDeploymentService;

import java.io.IOException;
import java.util.Map;

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
            write(exchange, 400, Map.of("error", "malformed json body"));
        } catch (IllegalArgumentException ex) {
            write(exchange, 400, Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            write(exchange, 500, Map.of("error", ex.getMessage()));
        }
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
