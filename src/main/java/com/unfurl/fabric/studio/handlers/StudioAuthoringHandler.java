package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioAccessDecision;
import com.unfurl.fabric.studio.StudioAccessPolicy;
import com.unfurl.fabric.studio.StudioAuthoringConverseRequest;
import com.unfurl.fabric.studio.StudioCatalogService;
import com.unfurl.fabric.studio.StudioPrincipal;

import java.io.IOException;
import java.util.Map;

public final class StudioAuthoringHandler {
    private final StudioCatalogService service;
    private final ObjectMapper mapper;
    private final StudioAccessPolicy accessPolicy;

    public StudioAuthoringHandler(StudioCatalogService service, ObjectMapper mapper) {
        this(service, mapper, new StudioAccessPolicy(tenantHeaderRequired()));
    }

    public StudioAuthoringHandler(StudioCatalogService service, ObjectMapper mapper, StudioAccessPolicy accessPolicy) {
        this.service = service == null ? new StudioCatalogService() : service;
        this.mapper = mapper;
        this.accessPolicy = accessPolicy == null ? new StudioAccessPolicy(false) : accessPolicy;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/studio/authoring/converse".equals(exchange.getRequestURI().getPath())) {
                write(exchange, 404, Map.of("error", "unknown Studio authoring route"));
                return;
            }
            StudioAuthoringConverseRequest request = mapper.readValue(
                    exchange.getRequestBody(),
                    StudioAuthoringConverseRequest.class);
            StudioAccessDecision access = authorize(exchange, request.tenantId());
            if (!access.allowed()) {
                write(exchange, 403, Map.of("error", access.reason()));
                return;
            }
            write(exchange, 200, service.converseAuthoring(request));
        } catch (JsonProcessingException ex) {
            write(exchange, 400, Map.of("error", "malformed json body"));
        } catch (IllegalArgumentException ex) {
            write(exchange, 400, Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            write(exchange, 500, Map.of("error", ex.getMessage()));
        }
    }

    private StudioAccessDecision authorize(HttpExchange exchange, String routeTenant) {
        return accessPolicy.authorize(StudioPrincipal.fromHeaders(
                routeTenant,
                exchange.getRequestHeaders().getFirst("X-Unfurl-Tenant"),
                exchange.getRequestHeaders().getFirst("X-Unfurl-User"),
                exchange.getRequestHeaders().getFirst("X-Unfurl-Tenant-Memberships")));
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean tenantHeaderRequired() {
        String value = System.getProperty("unfurl.studio.requireTenantHeader");
        if (value == null || value.isBlank()) {
            value = System.getenv("UNFURL_STUDIO_REQUIRE_TENANT_HEADER");
        }
        return "true".equalsIgnoreCase(value);
    }
}
