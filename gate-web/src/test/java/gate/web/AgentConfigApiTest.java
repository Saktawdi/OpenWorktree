package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/** S3 AgentConfig CRUD via HTTP (执行文档-后端-web §4.1, §9.5 A17). */
@Tag("slow")
class AgentConfigApiTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;

    @BeforeEach
    void setUp() {
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void crud_round_trip() throws Exception {
        HttpResponse<String> create = post("/api/agent-configs", """
                {"id":"claude-test","name":"Claude Test","cli":"CLAUDE","provider_id":"manual",
                 "model":"claude-test","system_prompt":null,"extra_flags":[],"description":"test"}
                """);
        assertEquals(201, create.statusCode(), create.body());

        HttpResponse<String> list = get("/api/agent-configs");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"id\":\"claude-test\""), list.body());

        HttpResponse<String> detail = get("/api/agent-configs/claude-test");
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"name\":\"Claude Test\""), detail.body());

        HttpResponse<String> updated = put("/api/agent-configs/claude-test", """
                {"name":"Claude Updated","cli":"CLAUDE","provider_id":"manual","model":"claude-test",
                 "system_prompt":"be careful","extra_flags":["--verbose"],"description":"updated"}
                """);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"name\":\"Claude Updated\""), updated.body());

        HttpResponse<String> deleted = delete("/api/agent-configs/claude-test");
        assertEquals(200, deleted.statusCode(), deleted.body());

        HttpResponse<String> after = get("/api/agent-configs/claude-test");
        assertEquals(400, after.statusCode(), after.body());
    }

    @Test
    void first_boot_seeds_default_opencode_agent() throws Exception {
        HttpResponse<String> list = get("/api/agent-configs");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"id\":\"opencode-default\""), list.body());

        HttpResponse<String> detail = get("/api/agent-configs/opencode-default");
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"cli\":\"OPENCODE\""), detail.body());
        // 默认员工不绑定 provider/model：完全跟随本机 opencode 自身配置。
        assertTrue(detail.body().contains("\"provider_id\":null"), detail.body());
        assertTrue(detail.body().contains("\"model\":null"), detail.body());
    }

    @Test
    void cli_profile_can_leave_provider_and_model_to_the_cli() throws Exception {
        HttpResponse<String> create = post("/api/agent-configs", """
                {"id":"opencode-bare","name":"OpenCode Bare","cli":"OPENCODE",
                 "system_prompt":null,"extra_flags":[],"description":"uses CLI config"}
                """);
        assertEquals(201, create.statusCode(), create.body());
        assertTrue(create.body().contains("\"provider_id\":null"), create.body());
        assertTrue(create.body().contains("\"model\":null"), create.body());

        HttpResponse<String> detail = get("/api/agent-configs/opencode-bare");
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"provider_id\":null"), detail.body());
        assertTrue(detail.body().contains("\"model\":null"), detail.body());
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
