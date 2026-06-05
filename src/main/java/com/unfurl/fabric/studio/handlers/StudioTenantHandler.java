package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioCatalogAdmissionRequest;
import com.unfurl.fabric.studio.StudioCatalogService;
import com.unfurl.fabric.studio.StudioCreateAssemblyRequest;
import com.unfurl.fabric.studio.StudioNeedsExtractionRequest;
import com.unfurl.fabric.studio.StudioSaveDraftRequest;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class StudioTenantHandler {
    private final StudioCatalogService service;
    private final ObjectMapper mapper;

    public StudioTenantHandler(StudioCatalogService service, ObjectMapper mapper) {
        this.service = service == null ? new StudioCatalogService() : service;
        this.mapper = mapper;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try {
            Route route = Route.parse(exchange.getRequestURI().getPath());
            if ("GET".equals(exchange.getRequestMethod()) && route.catalogList()) {
                write(exchange, 200, service.listCatalogVisuals(route.tenantId()));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.catalogAdmission()) {
                StudioCatalogAdmissionRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioCatalogAdmissionRequest.class);
                write(exchange, 200, service.admit(route.tenantId(), request));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.needsExtraction()) {
                StudioNeedsExtractionRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioNeedsExtractionRequest.class);
                write(exchange, 200, service.extractNeeds(route.tenantId(), route.assemblyId(), request));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.assemblyList()) {
                write(exchange, 200, service.listAssemblies(route.tenantId()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.dynamicDcp()) {
                write(exchange, 200, service.dynamicDcpProjection(route.tenantId(), route.assemblyId()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.replacementCandidates()) {
                write(exchange, 200, service.replacementCandidates(
                        route.tenantId(),
                        route.assemblyId(),
                        queryParam(exchange, "componentNodeId")));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.assemblyCreate()) {
                StudioCreateAssemblyRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioCreateAssemblyRequest.class);
                write(exchange, 200, service.createAssembly(route.tenantId(), request));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.saveDraft()) {
                StudioSaveDraftRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioSaveDraftRequest.class);
                write(exchange, 200, service.saveDraft(route.tenantId(), route.assemblyId(), request));
                return;
            }
            write(exchange, 404, Map.of("error", "unknown Studio tenant route"));
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

    private record Route(String tenantId, String assemblyId, String tail) {
        static Route parse(String path) {
            String prefix = "/studio/tenants/";
            if (!path.startsWith(prefix)) {
                throw new IllegalArgumentException("tenant route must start with " + prefix);
            }
            String remainder = path.substring(prefix.length());
            int slash = remainder.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("tenant id is required");
            }
            String tenant = decode(remainder.substring(0, slash));
            String tail = remainder.substring(slash + 1);
            String assembly = "";
            String assemblyPrefix = "assemblies/";
            if (tail.startsWith(assemblyPrefix)) {
                String assemblyRemainder = tail.substring(assemblyPrefix.length());
                int assemblySlash = assemblyRemainder.indexOf('/');
                if (assemblySlash >= 0) {
                    assembly = decode(assemblyRemainder.substring(0, assemblySlash));
                    tail = assemblyPrefix + "{assemblyId}/" + assemblyRemainder.substring(assemblySlash + 1);
                }
            }
            return new Route(tenant, assembly, tail);
        }

        boolean catalogList() {
            return "catalog".equals(tail);
        }

        boolean catalogAdmission() {
            return "catalog/admissions".equals(tail);
        }

        boolean needsExtraction() {
            return "assemblies/{assemblyId}/needs/extract".equals(tail);
        }

        boolean assemblyList() {
            return "assemblies".equals(tail);
        }

        boolean assemblyCreate() {
            return "assemblies".equals(tail);
        }

        boolean dynamicDcp() {
            return "assemblies/{assemblyId}/dynamic-dcp".equals(tail);
        }

        boolean replacementCandidates() {
            return "assemblies/{assemblyId}/dynamic-dcp/replacements".equals(tail);
        }

        boolean saveDraft() {
            return "assemblies/{assemblyId}/drafts/save".equals(tail);
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
