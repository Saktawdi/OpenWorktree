package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gate.web.service.OpenCodeConfigService;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OpenCode provider-file CRUD over HTTP (the ai-toolbox-style management surface). The service
 * reads {@code opencode.config.path} on every request, so pointing it at a temp file keeps the
 * user's real {@code ~/.config/opencode} out of the test.
 */
class OpenCodeProviderApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private Path configFile;
    private com.sun.net.httpserver.HttpServer upstream;
    private int upstreamPort;

    @BeforeEach
    void setUp() throws Exception {
        // Fake OpenAI-compatible upstream: /v1/models + /v1/chat/completions (mock-error 500s).
        upstream = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/models", ex -> {
            byte[] body = """
                    {"data":[{"id":"mock-alpha"},{"id":"mock-beta"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.createContext("/v1/chat/completions", ex -> {
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            String req = new String(reqBody, StandardCharsets.UTF_8);
            int status;
            byte[] body;
            if (req.contains("mock-error")) {
                status = 500;
                body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = """
                        {"choices":[{"message":{"content":"pong"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();

        configFile = Files.createTempDirectory("opencode-cfg-test-").resolve("opencode.json");
        Files.writeString(configFile, """
                {
                  "$schema": "https://opencode.ai/config.json",
                  "model": "github-copilot/claude-sonnet-4.5",
                  "provider": {
                    "github-copilot": {
                      "npm": "@ai-sdk/openai-compatible",
                      "name": "GitHub Copilot",
                      "options": {
                        "baseURL": "https://api.githubcopilot.com/",
                        "apiKey": "tok-123"
                      },
                      "models": {
                        "gpt-4.1": { "name": "GPT-4.1" }
                      }
                    }
                  },
                  "mcp": { "local-only": { "type": "local" } }
                }
                """);
        System.setProperty(OpenCodeConfigService.CONFIG_PATH_PROPERTY, configFile.toString());
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(OpenCodeConfigService.CONFIG_PATH_PROPERTY);
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
    void list_returns_existing_providers_and_config_path() throws Exception {
        HttpResponse<String> res = get("/api/opencode/providers");
        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = JSON.readValue(res.body(), Map.class);
        assertEquals(configFile.toString(), body.get("config_path"));
        assertEquals(Boolean.TRUE, body.get("config_exists"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> providers =
                (java.util.List<Map<String, Object>>) body.get("providers");
        assertEquals(1, providers.size(), res.body());
        Map<String, Object> p = providers.get(0);
        assertEquals("github-copilot", p.get("key"));
        assertEquals("GitHub Copilot", p.get("name"));
        assertEquals("https://api.githubcopilot.com/", p.get("base_url"));
        assertEquals("tok-123", p.get("api_key"));
        assertEquals(1, p.get("model_count"));
    }

    @Test
    void create_update_delete_round_trip_preserves_other_keys() throws Exception {
        HttpResponse<String> created = post("/api/opencode/providers/deepseek", """
                {"name":"DeepSeek","npm":"@ai-sdk/openai-compatible",
                 "base_url":"https://api.deepseek.com/v1","api_key":"sk-xyz",
                 "models":["deepseek-chat","deepseek-reasoner"]}
                """);
        assertEquals(201, created.statusCode(), created.body());

        // Other top-level keys survive the write untouched.
        String saved = Files.readString(configFile);
        assertTrue(saved.contains("\"$schema\""), saved);
        assertTrue(saved.contains("github-copilot"), saved);
        assertTrue(saved.contains("\"model\""), saved);
        assertTrue(saved.contains("local-only"), saved);

        HttpResponse<String> list = get("/api/opencode/providers");
        assertTrue(list.body().contains("\"key\":\"deepseek\""), list.body());

        // Update only the API key: name/models must survive the merge.
        HttpResponse<String> updated = put("/api/opencode/providers/deepseek", """
                {"api_key":"sk-rotated"}
                """);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("sk-rotated"), updated.body());
        assertTrue(updated.body().contains("deepseek-reasoner"), updated.body());
        assertTrue(updated.body().contains("DeepSeek"), updated.body());

        HttpResponse<String> deleted = delete("/api/opencode/providers/deepseek");
        assertEquals(200, deleted.statusCode(), deleted.body());
        HttpResponse<String> after = get("/api/opencode/providers");
        assertFalse(after.body().contains("deepseek"), after.body());

        // A .bak side copy of the pre-write file must exist.
        assertTrue(Files.exists(configFile.resolveSibling("opencode.json.bak")));
    }

    @Test
    void create_conflicts_with_existing_key() throws Exception {
        HttpResponse<String> res = post("/api/opencode/providers/github-copilot", """
                {"name":"Dup"}
                """);
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("already exists"), res.body());
    }

    @Test
    void delete_unknown_key_is_rejected() throws Exception {
        HttpResponse<String> res = delete("/api/opencode/providers/does-not-exist");
        assertEquals(400, res.statusCode(), res.body());
    }

    @Test
    void fetch_models_pulls_upstream_model_list() throws Exception {
        HttpResponse<String> res = post("/api/opencode/models/fetch", """
                {"base_url":"http://127.0.0.1:%d/v1","api_key":"sk-fake"}
                """.formatted(upstreamPort));
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("mock-alpha"), res.body());
        assertTrue(res.body().contains("mock-beta"), res.body());
    }

    @Test
    void fetch_models_rejects_non_http_base_url() throws Exception {
        HttpResponse<String> res = post("/api/opencode/models/fetch", """
                {"base_url":"ftp://example.com"}
                """);
        assertEquals(400, res.statusCode(), res.body());
    }

    @Test
    void test_model_reports_ok_with_reply() throws Exception {
        HttpResponse<String> res = post("/api/opencode/models/test", """
                {"base_url":"http://127.0.0.1:%d/v1","api_key":"sk-fake","model":"mock-alpha"}
                """.formatted(upstreamPort));
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"ok\":true"), res.body());
        assertTrue(res.body().contains("pong"), res.body());
    }

    @Test
    void test_model_reports_failure_as_data() throws Exception {
        HttpResponse<String> res = post("/api/opencode/models/test", """
                {"base_url":"http://127.0.0.1:%d/v1","api_key":"sk-fake","model":"mock-error"}
                """.formatted(upstreamPort));
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"ok\":false"), res.body());
        assertTrue(res.body().contains("500"), res.body());
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

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .DELETE().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
