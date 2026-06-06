package com.unfurl.fabric.studio;

import com.sun.net.httpserver.HttpServer;
import com.unfurl.fabric.studio.handlers.StudioTenantHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class StudioTenantHandlerTest {

    @Test
    void enforcesTenantMembershipWhenEnabled() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/studio/tenants", new StudioTenantHandler(
                new StudioCatalogService(),
                StudioJson.mapper(),
                true)::handle);
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/studio/tenants/tenant-a/catalog");

            HttpResponse<String> missing = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> notMember = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).header("X-Unfurl-Tenant", "tenant-a").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> matched = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri)
                            .header("X-Unfurl-Tenant", "tenant-a")
                            .header("X-Unfurl-User", "user-1")
                            .header("X-Unfurl-Tenant-Memberships", "tenant-a,tenant-b")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(missing.statusCode()).isEqualTo(403);
            assertThat(missing.body()).contains("tenant header does not match");
            assertThat(notMember.statusCode()).isEqualTo(403);
            assertThat(notMember.body()).contains("authenticated Studio user is required");
            assertThat(matched.statusCode()).isEqualTo(200);
            assertThat(matched.body()).contains("catalogHash");
        } finally {
            server.stop(0);
        }
    }
}
