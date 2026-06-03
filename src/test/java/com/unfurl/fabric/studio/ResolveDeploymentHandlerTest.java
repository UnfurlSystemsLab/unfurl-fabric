package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ResolveDeploymentHandlerTest {

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        try (StudioServer server = started()) {
            HttpResponse<String> response = post(server, "{not-json", "http://localhost:5173");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("malformed json body");
        }
    }

    @Test
    void corsReflectsOnlyAllowedDevOrigins() throws Exception {
        try (StudioServer server = started()) {
            assertThat(options(server, "http://localhost:5173")
                    .headers().firstValue("Access-Control-Allow-Origin"))
                    .contains("http://localhost:5173");
            assertThat(options(server, "http://127.0.0.1:5173")
                    .headers().firstValue("Access-Control-Allow-Origin"))
                    .contains("http://127.0.0.1:5173");
            assertThat(options(server, "http://evil.example")
                    .headers().firstValue("Access-Control-Allow-Origin"))
                    .isEmpty();
        }
    }

    private StudioServer started() throws Exception {
        StudioServer server = new StudioServer("127.0.0.1", 0);
        server.start();
        return server;
    }

    private HttpResponse<String> post(StudioServer server, String body, String origin) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(server))
                        .header("Origin", origin)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> options(StudioServer server, String origin) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(server))
                        .header("Origin", origin)
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(StudioServer server) {
        return URI.create("http://127.0.0.1:" + server.port() + "/studio/deployment/resolve");
    }
}
