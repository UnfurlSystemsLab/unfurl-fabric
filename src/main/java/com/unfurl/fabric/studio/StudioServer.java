package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.unfurl.fabric.studio.handlers.ResolveDeploymentHandler;
import com.unfurl.fabric.studio.handlers.StudioAuthoringHandler;
import com.unfurl.fabric.studio.handlers.StudioTenantHandler;
import com.unfurl.fabric.studio.handlers.StudioToolHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Ports & Adapters HTTP Adapter: hosts the lightweight Fabric Studio API on the JDK
 * {@link HttpServer}. The server owns a concurrent request executor because live
 * Server-Sent Event streams intentionally remain open while operators continue
 * issuing catalog, intent, heartbeat, and compile requests.
 */
public final class StudioServer implements AutoCloseable {
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 7878;

    /**
     * Any HTTP loopback origin is accepted in dev: {@code http://localhost(:port)?}
     * or {@code http://127.0.0.1(:port)?}. Pinning to a single port (e.g. 5173)
     * is too brittle in practice because Vite picks the next available port
     * when its default is taken, drifting to 5174, 5175, 5177, etc. The
     * loopback-only invariant from §2.5 Rule 2 is preserved: HTTPS, non-loopback
     * hostnames, and non-loopback IPv4 addresses are all rejected.
     */
    static final Pattern ALLOWED_DEV_ORIGIN = Pattern.compile(
            "^http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?$");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private final String bindAddress;
    private final StudioCatalogService catalogService;
    private final StudioSessionEventBus eventBus;

    /**
     * Convenience constructor: starts a loopback Studio server on the default port.
     */
    public StudioServer() throws IOException {
        this(DEFAULT_BIND_ADDRESS, DEFAULT_PORT);
    }

    /**
     * Convenience constructor: starts a Studio server using the default state store.
     */
    public StudioServer(String bindAddress, int port) throws IOException {
        this(bindAddress, port, new StudioCatalogService(new StudioStateStore(StudioStateStore.defaultPath())));
    }

    /**
     * Test/local constructor: hosts Studio routes with an injected catalog service.
     */
    public StudioServer(String bindAddress, int port, StudioCatalogService catalogService) throws IOException {
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? DEFAULT_BIND_ADDRESS : bindAddress;
        this.server = HttpServer.create(new InetSocketAddress(this.bindAddress, port), 0);
        this.executor = createExecutor();
        this.server.setExecutor(executor);
        this.mapper = StudioJson.mapper();
        this.catalogService = catalogService == null
                ? new StudioCatalogService(new StudioStateStore(StudioStateStore.defaultPath()))
                : catalogService;
        this.eventBus = null;
        DcpAuthoringClient.fromEnvironment().ifPresent(this.catalogService::useAuthoringInvocable);
        routes();
    }

    /**
     * Microservice constructor: binds Studio to configured state, assets, and event-bus adapters.
     */
    public StudioServer(StudioMicroserviceConfig config) throws IOException {
        StudioMicroserviceConfig safe = config == null ? StudioMicroserviceConfig.defaults() : config;
        this.bindAddress = safe.bindAddress();
        this.server = HttpServer.create(new InetSocketAddress(this.bindAddress, safe.port()), 0);
        this.executor = createExecutor();
        this.server.setExecutor(executor);
        this.mapper = StudioJson.mapper();
        this.eventBus = StudioEventBusFactory.create(safe);
        this.catalogService = new StudioCatalogService(
                new StudioStateStore(safe.statePath()),
                safe.assetRoot(),
                eventBus);
        DcpAuthoringClient.fromEnvironment().ifPresent(catalogService::useAuthoringInvocable);
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

    /**
     * Lifecycle method: stops request handling, interrupts long-lived event stream workers, and
     * then closes the optional external event-bus adapter.
     */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        if (eventBus != null) {
            eventBus.close();
        }
    }

    /**
     * Factory: creates named daemon request workers so live event streams cannot
     * starve regular Studio API calls in the JDK {@link HttpServer}.
     */
    private ExecutorService createExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "unfurl-studio-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    private void routes() {
        server.createContext("/health", withCors(exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                write(exchange, 405, Map.of("error", "method not allowed: " + exchange.getRequestMethod()));
                return;
            }
            write(exchange, 200, Map.of(
                    "status", "UP",
                    "service", "unfurl-fabric-studio",
                    "eventBus", catalogService.eventBusHealth()));
        }));
        server.createContext("/live", withCors(exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                write(exchange, 405, Map.of("error", "method not allowed: " + exchange.getRequestMethod()));
                return;
            }
            write(exchange, 200, Map.of("status", "UP"));
        }));
        server.createContext("/ready", withCors(exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                write(exchange, 405, Map.of("error", "method not allowed: " + exchange.getRequestMethod()));
                return;
            }
            StudioEventBusHealth eventBusHealth = catalogService.eventBusHealth();
            int status = "UP".equals(eventBusHealth.status()) ? 200 : 503;
            write(exchange, status, Map.of(
                    "status", status == 200 ? "READY" : "NOT_READY",
                    "eventBus", eventBusHealth));
        }));
        ResolveDeploymentHandler resolveDeployment = new ResolveDeploymentHandler(
                new StudioDeploymentService(),
                catalogService,
                mapper);
        server.createContext("/studio/deployment/resolve", withCors(resolveDeployment::handle));
        StudioAuthoringHandler authoringHandler = new StudioAuthoringHandler(catalogService, mapper);
        server.createContext("/studio/authoring", withCors(authoringHandler::handle));
        StudioToolHandler toolHandler = new StudioToolHandler(new StudioToolGateway(catalogService, mapper), mapper);
        server.createContext("/studio/tools", withCors(toolHandler::handle));
        StudioTenantHandler tenantHandler = new StudioTenantHandler(catalogService, mapper);
        server.createContext("/studio/tenants", withCors(tenantHandler::handle));
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
        if (origin != null && ALLOWED_DEV_ORIGIN.matcher(origin).matches()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            exchange.getResponseHeaders().set(
                    "Access-Control-Allow-Headers",
                    "Content-Type, X-Unfurl-Tenant, X-Unfurl-User, X-Unfurl-Tenant-Memberships");
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
