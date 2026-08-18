package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V5 runtime/model-fetch endpoints (web console): the real runtime-environment snapshot, and the
 * provider model pull against a local OpenAI-compatible upstream stub.
 */
class RuntimeAndModelFetchTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private HttpServer upstream;

    @BeforeEach
    void setUp() throws IOException {
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newSingleThreadExecutor());
        upstream.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
        if (server != null) {
            server.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void runtime_reports_real_environment() throws Exception {
        HttpResponse<String> res = get("/api/runtime");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"service\":\"gate-web\""), res.body());
        assertTrue(res.body().contains("\"uptime_seconds\":"), res.body());
        assertTrue(res.body().contains("\"java\":"), res.body());
        assertTrue(res.body().contains("\"os\":"), res.body());
        // The harness runs with the real git binary, so the probe must succeed.
        assertTrue(res.body().contains("\"git\":{\"name\":\"git\",\"available\":true"), res.body());
        // Agent CLI probes carry name/available/version regardless of the machine's installs.
        assertTrue(res.body().contains("\"agent_clis\":[{\"name\":\"claude\",\"available\":"), res.body());
        assertTrue(res.body().contains("{\"name\":\"opencode\",\"available\":"), res.body());
        assertTrue(res.body().contains("\"counts\":{\"tickets\":"), res.body());
        assertTrue(res.body().contains("\"session_port_range\":"), res.body());
    }

    @Test
    void agent_runtime_catalog_reports_detected_clis_and_model_source() throws Exception {
        HttpResponse<String> res = get("/api/agent-runtimes");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"agent_runtimes\":["), res.body());
        assertTrue(res.body().contains("\"name\":\"claude\""), res.body());
        assertTrue(res.body().contains("\"name\":\"opencode\""), res.body());
        assertTrue(res.body().contains("\"models\":["), res.body());
        assertTrue(res.body().contains("\"model_source\":"), res.body());
    }

    @Test
    void provider_models_fetch_pulls_and_persists_upstream_list() throws Exception {
        String providerId = "stubgw";
        HttpResponse<String> created = post("/api/providers", "{\"id\":\"" + providerId
                + "\",\"name\":\"Stub Gateway\",\"base_url\":\"http://127.0.0.1:"
                + upstream.getAddress().getPort() + "/v1\",\"type\":\"openai-compatible\"}");
        assertEquals(200, created.statusCode(), created.body());

        HttpResponse<String> fetched = post("/api/providers/" + providerId + "/models/fetch", "");
        assertEquals(200, fetched.statusCode(), fetched.body());
        // Sorted, de-duplicated, persisted.
        assertTrue(fetched.body().contains("\"models\":[\"a-model\",\"z-model\"]"), fetched.body());
        assertTrue(fetched.body().contains("\"model_count\":2"), fetched.body());

        HttpResponse<String> list = get("/api/providers");
        assertTrue(list.body().contains("\"a-model\""), list.body());
    }

    @Test
    void manual_provider_has_no_upstream() throws Exception {
        HttpResponse<String> res = post("/api/providers/manual/models/fetch", "");
        assertTrue(res.statusCode() >= 400, "manual provider must not be fetchable: " + res.body());
        assertTrue(res.body().contains("manual provider has no upstream"), res.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
