package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 设置中心端点（V5 web console）：gate.toml 分组视图/写回与 MCP 工具状态。视图/写回直连
 * {@link SettingsRoutes}（真实临时 toml）；嵌套的 OverHttp 经真实 HTTP 服务端验证路由挂载与
 * 鉴权路径（WebHarness 不携带 toml 路径 → gate-toml 应答 USAGE，mcp/status 应答 disabled）。
 */
class SettingsRoutesTest {

    @TempDir
    Path dir;

    private Path toml;
    private SettingsRoutes routes;

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
        routes = new SettingsRoutes(toml);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> viewBody() {
        // 测试侧直接复用路由返回的 body map（生产路径由 ApiHandler 序列化为 JSON）。
        return (Map<String, Object>) routes.gateTomlView().body();
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

    @Test
    void viewCoversCatalogInFixedSectionOrder() {
        Map<String, Object> body = viewBody();
        assertEquals(toml.toAbsolutePath().normalize().toString(), body.get("toml_path"));
        assertEquals(Boolean.TRUE, body.get("restart_required"));
        List<Map<String, Object>> sections = (List<Map<String, Object>>) body.get("sections");
        List<String> order = sections.stream().map(s -> (String) s.get("section")).toList();
        assertEquals(List.of("", "gate_identity", "web", "session", "policy", "engine", "agent"), order);

        Map<String, Object> timeout = keyOf(body, "session.start_timeout_seconds");
        assertEquals(60L, timeout.get("value"));
        assertEquals("int", timeout.get("type"));
        assertEquals(Boolean.TRUE, timeout.get("editable"));
        assertEquals(60L, timeout.get("default"));

        assertEquals(Boolean.FALSE, keyOf(body, "db_path").get("editable"));
        // 文件未写的键：value 为 null（前端展示 default）。
        assertEquals(null, keyOf(body, "engine.model").get("value"));

        Map<String, Object> origins = keyOf(body, "web.allowed_origins");
        assertEquals("string_list", origins.get("type"));
        assertEquals(List.of("127.0.0.1", "localhost"), origins.get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatePersistsAndNextViewReflectsIt() {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("session.start_timeout_seconds", 90L);
        ApiRoutes.Response res = routes.gateTomlUpdate(Map.of("updates", updates));
        Map<String, Object> body = (Map<String, Object>) res.body();
        assertEquals(200, res.status());
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals(List.of("session.start_timeout_seconds"), body.get("updated"));
        assertEquals(Boolean.TRUE, body.get("restart_required"));

        assertEquals(90L, keyOf(viewBody(), "session.start_timeout_seconds").get("value"));
        assertTrue(Files.exists(toml.resolveSibling("gate.toml.bak")), "write must leave a .bak");
    }

    @Test
    void updateRejectsNonEditableAndUnknownKeysAndMissingEnvelope() {
        GateException nonEditable = assertThrows(GateException.class,
                () -> routes.gateTomlUpdate(Map.of("updates", Map.of("db_path", "x"))));
        assertEquals(GateErrorCode.USAGE, nonEditable.code());

        GateException unknown = assertThrows(GateException.class,
                () -> routes.gateTomlUpdate(Map.of("updates", Map.of("typo_key", 1L))));
        assertEquals(GateErrorCode.USAGE, unknown.code());

        GateException noUpdates = assertThrows(GateException.class,
                () -> routes.gateTomlUpdate(new HashMap<>()));
        assertEquals(GateErrorCode.USAGE, noUpdates.code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpStatusListsToolsWithDomainCounts() {
        Map<String, Object> body = (Map<String, Object>) routes.mcpStatus().body();
        assertEquals("enabled", body.get("provisioning"));
        assertEquals("stdio", body.get("transport"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertFalse(tools.isEmpty(), "registry is seeded with tools");
        int agent = (Integer) body.get("agent_tool_count");
        int human = (Integer) body.get("human_tool_count");
        assertEquals(tools.size(), agent + human, "every tool belongs to exactly one domain");
        for (Map<String, Object> tool : tools) {
            assertTrue("agent".equals(tool.get("domain")) || "human".equals(tool.get("domain")),
                    "unexpected domain: " + tool.get("domain"));
            assertNotNull(tool.get("description"));
        }
        List<Map<String, String>> cli = (List<Map<String, String>>) body.get("cli_integration");
        assertEquals(2, cli.size(), "claude + opencode integration notes");
    }

    @Test
    void unknownTomlPathRefusesViewAndReportsMcpDisabled() {
        SettingsRoutes bare = new SettingsRoutes(null);
        GateException e = assertThrows(GateException.class, bare::gateTomlView);
        assertEquals(GateErrorCode.USAGE, e.code());
        assertEquals("disabled", ((Map<?, ?>) bare.mcpStatus().body()).get("provisioning"));
    }

    /** 经真实 WebServer/ApiHandler/AuthFilter 的路由挂载与鉴权验证。 */
    @Nested
    class OverHttp {

        private WebHarness harness;
        private WebServer server;
        private HttpClient client;
        private String base;
        private String token;

        @BeforeEach
        void setUpServer() throws IOException {
            harness = new WebHarness();
            server = new WebServer(harness.components());
            server.start();
            client = HttpClient.newHttpClient();
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

        private HttpResponse<String> httpGet(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }
}
