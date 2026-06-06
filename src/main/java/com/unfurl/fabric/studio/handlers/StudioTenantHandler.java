package com.unfurl.fabric.studio.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.unfurl.fabric.studio.StudioCatalogAdmissionRequest;
import com.unfurl.fabric.studio.StudioCatalogService;
import com.unfurl.fabric.studio.StudioCollaborator;
import com.unfurl.fabric.studio.StudioCompileDraftCandidateRequest;
import com.unfurl.fabric.studio.StudioCreateAssemblyRequest;
import com.unfurl.fabric.studio.StudioCreateDraftCompositionRequest;
import com.unfurl.fabric.studio.StudioIntentRequest;
import com.unfurl.fabric.studio.StudioLayoutStateRequest;
import com.unfurl.fabric.studio.StudioNeedsExtractionRequest;
import com.unfurl.fabric.studio.StudioSaveDraftRequest;
import com.unfurl.fabric.studio.StudioSessionEvent;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class StudioTenantHandler {
    private final StudioCatalogService service;
    private final ObjectMapper mapper;
    private final boolean requireTenantHeader;

    public StudioTenantHandler(StudioCatalogService service, ObjectMapper mapper) {
        this(service, mapper, tenantHeaderRequired());
    }

    public StudioTenantHandler(StudioCatalogService service, ObjectMapper mapper, boolean requireTenantHeader) {
        this.service = service == null ? new StudioCatalogService() : service;
        this.mapper = mapper;
        this.requireTenantHeader = requireTenantHeader;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try {
            Route route = Route.parse(exchange.getRequestURI().getPath());
            if (!tenantAuthorized(exchange, route.tenantId())) {
                write(exchange, 403, Map.of("error", "tenant header does not match route tenant"));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.catalogList()) {
                write(exchange, 200, service.listCatalogVisuals(route.tenantId()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.asset()) {
                write(exchange, 200, service.visualAsset(route.tenantId(), route.assetId()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.assetContent()) {
                service.visualAssetContent(route.tenantId(), route.assetId(), queryParam(exchange, "sha256"))
                        .ifPresentOrElse(
                                content -> {
                                    try {
                                        writeBinary(exchange, 200, content.mediaType(), content.bytes());
                                    } catch (IOException ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                },
                                () -> {
                                    try {
                                        write(exchange, 404, Map.of("error", "asset content is unavailable or hash verification failed"));
                                    } catch (IOException ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
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
            if ("GET".equals(exchange.getRequestMethod()) && route.layout()) {
                write(exchange, 200, service.layout(route.tenantId(), route.assemblyId()));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.layout()) {
                StudioLayoutStateRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioLayoutStateRequest.class);
                write(exchange, 200, service.saveLayout(route.tenantId(), route.assemblyId(), request));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.sessions()) {
                StudioCreateDraftCompositionRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioCreateDraftCompositionRequest.class);
                write(exchange, 200, service.createDraftSession(route.tenantId(), route.assemblyId(), request));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.session()) {
                write(exchange, 200, service.draftSession(route.tenantId(), route.assemblyId(), route.sessionId()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && route.sessionEvents()) {
                writeEventStream(exchange, service.sessionEvent(route.tenantId(), route.assemblyId(), route.sessionId()));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.sessionHeartbeat()) {
                StudioCollaborator request = mapper.readValue(exchange.getRequestBody(), StudioCollaborator.class);
                write(exchange, 200, service.heartbeat(route.tenantId(), route.assemblyId(), route.sessionId(), request));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.sessionIntents()) {
                StudioIntentRequest request = mapper.readValue(exchange.getRequestBody(), StudioIntentRequest.class);
                write(exchange, 200, service.applyIntent(route.tenantId(), route.assemblyId(), route.sessionId(), request));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && route.sessionCompile()) {
                StudioCompileDraftCandidateRequest request = mapper.readValue(
                        exchange.getRequestBody(),
                        StudioCompileDraftCandidateRequest.class);
                write(exchange, 200, service.compileCandidate(route.tenantId(), route.assemblyId(), route.sessionId(), request));
                return;
            }
            write(exchange, 404, Map.of("error", "unknown Studio tenant route"));
        } catch (JsonProcessingException ex) {
            write(exchange, 400, Map.of("error", "malformed json body"));
        } catch (IllegalStateException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            write(exchange, 500, Map.of("error", ex.getMessage()));
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

    private void writeBinary(HttpExchange exchange, int status, String mediaType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.getResponseHeaders().set("Cache-Control", "public, immutable");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void writeEventStream(HttpExchange exchange, StudioSessionEvent event) throws IOException {
        byte[] json = mapper.writeValueAsBytes(event);
        String body = "retry: 3000\n"
                + "id: " + event.eventId() + "\n"
                + "event: session\n"
                + "data: " + new String(json, StandardCharsets.UTF_8) + "\n\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private boolean tenantAuthorized(HttpExchange exchange, String routeTenant) {
        if (!requireTenantHeader) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("X-Unfurl-Tenant");
        return routeTenant.equals(header);
    }

    private static boolean tenantHeaderRequired() {
        String value = System.getProperty("unfurl.studio.requireTenantHeader");
        if (value == null || value.isBlank()) {
            value = System.getenv("UNFURL_STUDIO_REQUIRE_TENANT_HEADER");
        }
        return "true".equalsIgnoreCase(value);
    }

    private record Route(String tenantId, String assemblyId, String assetId, String sessionId, String tail) {
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
            String assetId = "";
            String sessionId = "";
            String assemblyPrefix = "assemblies/";
            if (tail.startsWith(assemblyPrefix)) {
                String assemblyRemainder = tail.substring(assemblyPrefix.length());
                int assemblySlash = assemblyRemainder.indexOf('/');
                if (assemblySlash >= 0) {
                    assembly = decode(assemblyRemainder.substring(0, assemblySlash));
                    tail = assemblyPrefix + "{assemblyId}/" + assemblyRemainder.substring(assemblySlash + 1);
                }
            }
            String assetPrefix = "assets/";
            if (tail.startsWith(assetPrefix)) {
                String assetRemainder = tail.substring(assetPrefix.length());
                int assetSlash = assetRemainder.indexOf('/');
                assetId = decode(assetSlash >= 0 ? assetRemainder.substring(0, assetSlash) : assetRemainder);
                tail = assetSlash >= 0
                        ? assetPrefix + "{assetId}/" + assetRemainder.substring(assetSlash + 1)
                        : assetPrefix + "{assetId}";
            }
            String sessionPrefix = "assemblies/{assemblyId}/sessions/";
            if (tail.startsWith(sessionPrefix)) {
                String sessionRemainder = tail.substring(sessionPrefix.length());
                int sessionSlash = sessionRemainder.indexOf('/');
                sessionId = decode(sessionSlash >= 0 ? sessionRemainder.substring(0, sessionSlash) : sessionRemainder);
                tail = sessionSlash >= 0
                        ? sessionPrefix + "{sessionId}/" + sessionRemainder.substring(sessionSlash + 1)
                        : sessionPrefix + "{sessionId}";
            }
            return new Route(tenant, assembly, assetId, sessionId, tail);
        }

        boolean catalogList() {
            return "catalog".equals(tail);
        }

        boolean catalogAdmission() {
            return "catalog/admissions".equals(tail);
        }

        boolean asset() {
            return "assets/{assetId}".equals(tail);
        }

        boolean assetContent() {
            return "assets/{assetId}/content".equals(tail);
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

        boolean layout() {
            return "assemblies/{assemblyId}/layout".equals(tail);
        }

        boolean sessions() {
            return "assemblies/{assemblyId}/sessions".equals(tail);
        }

        boolean session() {
            return "assemblies/{assemblyId}/sessions/{sessionId}".equals(tail);
        }

        boolean sessionEvents() {
            return "assemblies/{assemblyId}/sessions/{sessionId}/events".equals(tail);
        }

        boolean sessionHeartbeat() {
            return "assemblies/{assemblyId}/sessions/{sessionId}/collaborators/heartbeat".equals(tail);
        }

        boolean sessionIntents() {
            return "assemblies/{assemblyId}/sessions/{sessionId}/intents".equals(tail);
        }

        boolean sessionCompile() {
            return "assemblies/{assemblyId}/sessions/{sessionId}/compile".equals(tail);
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
