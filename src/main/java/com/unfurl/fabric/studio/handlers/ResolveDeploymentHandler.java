package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioDeploymentResolveRequest;
import com.unfurl.fabric.studio.StudioDeploymentService;

import java.io.IOException;
import java.util.Map;

public final class ResolveDeploymentHandler {
    private final StudioDeploymentService service;
    private final ObjectMapper mapper;

    public ResolveDeploymentHandler(StudioDeploymentService service, ObjectMapper mapper) {
        this.service = service == null ? new StudioDeploymentService() : service;
        this.mapper = mapper;
    }

    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, Map.of("error", "method not allowed: " + exchange.getRequestMethod()));
            return;
        }
        try {
            StudioDeploymentResolveRequest request = mapper.readValue(
                    exchange.getRequestBody(),
                    StudioDeploymentResolveRequest.class);
            write(exchange, 200, service.resolveDeployment(request));
        } catch (JsonProcessingException ex) {
            write(exchange, 400, Map.of("error", "malformed json body"));
        } catch (IllegalArgumentException ex) {
            write(exchange, 400, Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            write(exchange, 500, Map.of("error", ex.getMessage()));
        }
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
