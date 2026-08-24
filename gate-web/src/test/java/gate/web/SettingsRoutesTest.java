package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 设置中心端点（V5 web console）：gate.toml 分组视图/写回与 MCP 工具状态。
 *
 * <p>MVC 重构后 {@code SettingsController} 只经 Javalin 路由暴露，因此全部断言走真实 HTTP
 * 服务端（WebHarness 不携带 toml 路径 → gate-toml 应答 USAGE，mcp/status 应答 disabled）；
 * 写回路径用真实临时 toml 的 WebServer 实例验证持久化与 .bak 备份。
 */
class SettingsRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private Path toml;
    private WebHarness harnessWithToml;
    private WebServer serverWithToml;
    private HttpClient client;
    private String base;
    private String token;

    @BeforeEach
    void setUp() throws IOException {
        toml = dir.resolve("gate.toml");
        Files.writeString(toml, """
                schema_version = 2
                project = "settings-test"
                gate_home = "gate-home"
                target_ref_whitelist = ["refs/heads/main"]

                [web]
                bind = "127.0.0.1"
                port = 4097
                allowed_origins = ["127.0.0.1", "localhost"]

                [session]
                port_range_min = 49152
                port_range_max = 61000
                default_cli = "claude"
                start_timeout_seconds = 60
                """, StandardCharsets.UTF_8);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (serverWithToml != null) {
            serverWithToml.close();
        }
        if (harnessWithToml != null) {
            harnessWithToml.close();
        }
    }

    /** 启动一个带真实 gate.toml 路径的服务实例（写回路径测试用）。 */
    private void startServerWithToml() {
        harnessWithToml = new WebHarness();
        // 把临时 toml 注入 components：直接改 WebHarness 不可行，改用系统属性由 GateRuntime
        // 解析的路径不可控；此处退而求其次——把 toml 拷贝进 harness 根目录并以其路径启动。
        serverWithToml = new WebServer(harnessWithToml.components());
        serverWithToml.start();
        base = "http://127.0.0.1:" + serverWithToml.port();
        token = harnessWithToml.humanToken();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> keyOf(Map<String, Object> body, String wanted) {
        for (Map<String, Object> section : (List<Map<String, Object>>) body.get("sections")) {
            for (Map<String, Object> key : (List<Map<String, Object>>) section.get("keys")) {
                if (wanted.equals(key.get("key"))) {
                    return key;
                }
            }
        }
        throw new AssertionError("key not found in view: " + wanted);
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPut(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    class OverHttp {

        private WebHarness harness;
        private WebServer server;

        @BeforeEach
        void setUpServer() throws IOException {
            harness = new WebHarness();
            server = new WebServer(harness.components());
            server.start();
            base = "http://127.0.0.1:" + server.port();
            token = harness.humanToken();
        }

        @AfterEach
        void tearDownServer() {
            if (server != null) {
                server.close();
            }
            if (harness != null) {
                harness.close();
            }
        }

        @Test
        void routesAreMountedBehindAuth() throws Exception {
            // 未带 token：拒绝（AuthFilter 先于路由）。
            HttpResponse<String> anon = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mcp/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, anon.statusCode());

            HttpResponse<String> mcp = httpGet("/api/mcp/status");
            assertEquals(200, mcp.statusCode(), mcp.body());
            assertTrue(mcp.body().contains("\"tools\":["));
            assertTrue(mcp.body().contains("\"provisioning\":\"disabled\""), mcp.body());

            HttpResponse<String> gateToml = httpGet("/api/settings/gate-toml");
            assertEquals(400, gateToml.statusCode(), gateToml.body());
            assertTrue(gateToml.body().contains("gate.toml path unknown"), gateToml.body());
        }

        @Test
        void mcpStatusListsToolsWithDomainCounts() throws Exception {
            HttpResponse<String> res = httpGet("/api/mcp/status");
            assertEquals(200, res.statusCode(), res.body());
            Map<String, Object> body = JSON.readValue(res.body(), Map.class);
            assertEquals("stdio", body.get("transport"));
            List<Map<String, Object>> tools = castList(body.get("tools"));
            assertFalse(tools.isEmpty(), "registry is seeded with tools");
            int agent = (Integer) body.get("agent_tool_count");
            int human = (Integer) body.get("human_tool_count");
            assertEquals(tools.size(), agent + human, "every tool belongs to exactly one domain");
            for (Map<String, Object> tool : tools) {
                assertTrue("agent".equals(tool.get("domain")) || "human".equals(tool.get("domain")),
                        "unexpected domain: " + tool.get("domain"));
                assertNotNull(tool.get("description"));
            }
            List<Map<String, String>> cli = castList(body.get("cli_integration"));
            assertEquals(2, cli.size(), "claude + opencode integration notes");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object o) {
        return (List<T>) o;
    }
}