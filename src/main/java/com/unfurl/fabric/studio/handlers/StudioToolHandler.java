package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioAccessDecision;
import com.unfurl.fabric.studio.StudioAccessPolicy;
import com.unfurl.fabric.studio.StudioPrincipal;
import com.unfurl.fabric.studio.StudioToolCallRequest;
import com.unfurl.fabric.studio.StudioToolCallResult;
import com.unfurl.fabric.studio.StudioToolGateway;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP Adapter: exposes Fabric Studio's Foundry-compatible tool gateway under
 * `/studio/tools/{toolName}`. The handler performs route validation, tenant
 * header checks, and canonical JSON error shaping before delegating to the
 * service-level gateway.
 */
public final class StudioToolHandler {
    private static final String PREFIX = "/studio/tools/";

    private final StudioToolGateway gateway;
    private final ObjectMapper mapper;
    private final StudioAccessPolicy accessPolicy;

    /**
     * Constructor: wires the tool gateway with the same mapper and access policy
     * style as the rest of the lightweight Studio HTTP API.
     */
    public StudioToolHandler(StudioToolGateway gateway, ObjectMapper mapper) {
        this(gateway, mapper, new StudioAccessPolicy(tenantHeaderRequired()));
    }

    /**
     * Constructor: accepts an explicit access policy for tests and embedded hosts
     * that choose whether tenant headers are enforced.
     */
    public StudioToolHandler(StudioToolGateway gateway, ObjectMapper mapper, StudioAccessPolicy accessPolicy) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.accessPolicy = accessPolicy == null ? new StudioAccessPolicy(false) : accessPolicy;
    }

    /**
     * HTTP entry point: validates the tool route, deserializes the Foundry-style
     * request, authorizes the tenant, and writes a canonical tool result.
     */
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, StudioToolCallResult.failure(
                    "FABRIC_TOOL_METHOD_NOT_ALLOWED",
                    "method not allowed: " + exchange.getRequestMethod()));
            return;
        }
        String routeToolName = routeToolName(exchange.getRequestURI().getPath());
        if (routeToolName.isBlank()) {
            write(exchange, 404, StudioToolCallResult.failure(
                    "FABRIC_TOOL_ROUTE_NOT_FOUND",
                    "unknown Studio tool route"));
            return;
        }
        try {
            StudioToolCallRequest body = mapper.readValue(exchange.getRequestBody(), StudioToolCallRequest.class);
            if (!body.toolName().isBlank() && !routeToolName.equals(body.toolName())) {
                write(exchange, 400, StudioToolCallResult.failure(
                        "FABRIC_TOOL_NAME_MISMATCH",
                        "route toolName does not match request toolName"));
                return;
            }
            StudioToolCallRequest request = body.withToolName(routeToolName);
            StudioAccessDecision access = authorize(exchange, gateway.tenantId(request));
            if (!access.allowed()) {
                write(exchange, 403, StudioToolCallResult.failure("FABRIC_TOOL_FORBIDDEN", access.reason()));
                return;
            }
            write(exchange, 200, gateway.execute(request));
        } catch (JsonProcessingException ex) {
            write(exchange, 400, StudioToolCallResult.failure("FABRIC_TOOL_MALFORMED", "malformed json body"));
        } catch (RuntimeException ex) {
            write(exchange, 500, StudioToolCallResult.failure("FABRIC_TOOL_HANDLER_ERROR", ex.getMessage()));
        }
    }

    /**
     * Route parser: decodes the path suffix after `/studio/tools/` as the logical
     * Foundry tool name.
     */
    private String routeToolName(String path) {
        if (path == null || !path.startsWith(PREFIX) || path.length() <= PREFIX.length()) {
            return "";
        }
        return URLDecoder.decode(path.substring(PREFIX.length()), StandardCharsets.UTF_8);
    }

    /**
     * Access adapter: projects the tool's tenant scope into the shared Studio
     * access-policy object.
     */
    private StudioAccessDecision authorize(HttpExchange exchange, String routeTenant) {
        return accessPolicy.authorize(StudioPrincipal.fromHeaders(
                routeTenant,
                exchange.getRequestHeaders().getFirst("X-Unfurl-Tenant"),
                exchange.getRequestHeaders().getFirst("X-Unfurl-User"),
                exchange.getRequestHeaders().getFirst("X-Unfurl-Tenant-Memberships")));
    }

    /**
     * Response writer: serializes one canonical tool result and closes the exchange.
     */
    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * Configuration helper: matches tenant-header enforcement behavior used by
     * other Studio handlers.
     */
    private static boolean tenantHeaderRequired() {
        String value = System.getProperty("unfurl.studio.requireTenantHeader");
        if (value == null || value.isBlank()) {
            value = System.getenv("UNFURL_STUDIO_REQUIRE_TENANT_HEADER");
        }
        return "true".equalsIgnoreCase(value);
    }
}
