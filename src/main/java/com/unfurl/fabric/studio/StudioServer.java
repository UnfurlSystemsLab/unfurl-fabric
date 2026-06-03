package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.unfurl.fabric.studio.handlers.ResolveDeploymentHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;

public final class StudioServer implements AutoCloseable {
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 7878;

    private static final Set<String> ALLOWED_DEV_ORIGINS = Set.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173");

    private final HttpServer server;
    private final ObjectMapper mapper;
    private final String bindAddress;

    public StudioServer() throws IOException {
        this(DEFAULT_BIND_ADDRESS, DEFAULT_PORT);
    }

    public StudioServer(String bindAddress, int port) throws IOException {
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? DEFAULT_BIND_ADDRESS : bindAddress;
        this.server = HttpServer.create(new InetSocketAddress(this.bindAddress, port), 0);
        this.mapper = StudioJson.mapper();
        routes();
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String bindAddress() {
        return bindAddress;
    }

    public boolean nonLoopbackBindWarningRequired() {
        return !DEFAULT_BIND_ADDRESS.equals(bindAddress) && !"localhost".equalsIgnoreCase(bindAddress);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void routes() {
        server.createContext("/health", withCors(exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                write(exchange, 405, Map.of("error", "method not allowed: " + exchange.getRequestMethod()));
                return;
            }
            write(exchange, 200, Map.of("status", "UP", "service", "unfurl-fabric-studio"));
        }));
        ResolveDeploymentHandler resolveDeployment = new ResolveDeploymentHandler(new StudioDeploymentService(), mapper);
        server.createContext("/studio/deployment/resolve", withCors(resolveDeployment::handle));
    }

    private HttpHandler withCors(HttpHandler delegate) {
        return exchange -> {
            applyCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            delegate.handle(exchange);
        };
    }

    private void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && ALLOWED_DEV_ORIGINS.contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
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
