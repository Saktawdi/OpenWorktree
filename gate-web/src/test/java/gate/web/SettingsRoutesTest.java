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

                [engine]
                cmd = "gate-review"
                args = ["--stdin"]
                timeout_seconds = 120
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

    /** 启动一个带真实 gate.toml 路径的服务实例（视图/写回路径测试用）。 */
    private void startServerWithToml() {
        harnessWithToml = new WebHarness(toml);
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

    @Nested
    class WithTomlFile {

        @BeforeEach
        void setUpServer() {
            startServerWithToml();
        }

        @AfterEach
        void tearDownServer() {
            if (serverWithToml != null) {
                serverWithToml.close();
                serverWithToml = null;
            }
            if (harnessWithToml != null) {
                harnessWithToml.close();
                harnessWithToml = null;
            }
        }

        @Test
        void viewEmitsSectionRelativeShortKeys() throws Exception {
            HttpResponse<String> res = httpGet("/api/settings/gate-toml");
            assertEquals(200, res.statusCode(), res.body());
            Map<String, Object> body = JSON.readValue(res.body(), Map.class);
            List<Map<String, Object>> sections = castList(body.get("sections"));
            for (Map<String, Object> section : sections) {
                String secName = String.valueOf(section.get("section"));
                List<Map<String, Object>> keys = castList(section.get("keys"));
                for (Map<String, Object> key : keys) {
                    String keyName = String.valueOf(key.get("key"));
                    assertFalse(keyName.contains("."),
                            "view must emit section-relative short keys, got: " + secName + " -> " + keyName);
                }
            }
            // 引擎区包含 provider_id / model 两个键（前端下拉框依赖该视图）
            Map<String, Object> providerKey = keyOf(body, "provider_id");
            assertNotNull(providerKey);
            assertTrue(Boolean.TRUE.equals(providerKey.get("editable")), providerKey.toString());
        }

        @Test
        @SuppressWarnings("unchecked")
        void engineKeysLandUnderEngineSection() throws Exception {
            HttpResponse<String> res = httpGet("/api/settings/gate-toml");
            assertEquals(200, res.statusCode(), res.body());
            Map<String, Object> body = JSON.readValue(res.body(), Map.class);
            List<Map<String, Object>> sections = castList(body.get("sections"));
            for (Map<String, Object> section : sections) {
                if (!"engine".equals(section.get("section"))) {
                    continue;
                }
                List<Map<String, Object>> keys = castList(section.get("keys"));
                List<String> names = new java.util.ArrayList<>();
                for (Map<String, Object> key : keys) {
                    names.add(String.valueOf(key.get("key")));
                }
                // 单引擎化后的目录键集：cmd/args 已废弃不再暴露，kind/idle_timeout_seconds/max_tokens 顶替加入。
                assertTrue(names.containsAll(List.of("kind", "timeout_seconds", "provider_id", "model",
                        "idle_timeout_seconds", "max_tokens")),
                        "engine section keys: " + names);
                // deprecated 键（cmd/args）不得出现在目录中——设置中心再也不应展示它。
                assertFalse(names.contains("cmd") || names.contains("args"),
                        "deprecated cmd/args must not appear in catalog: " + names);
                return;
            }
            throw new AssertionError("engine section missing in view");
        }

        /** 回归：前端拼接出的全键（如 engine.provider_id）必须能直接写回，且不再出现二次前缀。 */
        @Test
        void writeBackAcceptsFullKeysAndPersistsEngineProviderModel() throws Exception {
            HttpResponse<String> put = httpPut("/api/settings/gate-toml",
                    "{\"updates\":{\"engine.provider_id\":\"manual\",\"engine.model\":\"gpt-test\",\"policy.strictness\":\"BLOCKER_AND_WARNING\"}}");
            assertEquals(200, put.statusCode(), put.body());

            String raw = Files.readString(toml);
            assertTrue(raw.contains("strictness = \"BLOCKER_AND_WARNING\""), raw);
            // provider_id/model 应落在 [engine] 分区内，strictness 落在新增的 [policy] 分区内（行级写回保持分区）
            int engineIdx = raw.indexOf("[engine]");
            int policyIdx = raw.indexOf("[policy]");
            assertTrue(policyIdx > engineIdx, raw);
            String engineBlock = raw.substring(engineIdx, policyIdx);
            assertTrue(engineBlock.contains("provider_id = \"manual\""), raw);
            assertTrue(engineBlock.contains("model = \"gpt-test\""), raw);
            assertTrue(raw.indexOf("strictness") > policyIdx, raw);

            assertTrue(Files.exists(toml.resolveSibling(toml.getFileName() + ".bak")), ".bak backup expected");
        }

        /** 回归：历史上前端曾把双前缀键发给后端；即便如此也应显式拒绝而非静默写入。 */
        @Test
        void writeBackRejectsDoublePrefixedKey() throws Exception {
            HttpResponse<String> put = httpPut("/api/settings/gate-toml",
                    "{\"updates\":{\"engine.engine.provider_id\":\"manual\"}}");
            assertEquals(400, put.statusCode(), put.body());
            assertTrue(put.body().contains("unknown key: engine.engine.provider_id"), put.body());
            assertFalse(Files.readString(toml).contains("engine.engine"), "file must stay untouched");
        }

        private static String shortKey(String fullKey) {
            int dot = fullKey.indexOf('.');
            return dot < 0 ? fullKey : fullKey.substring(dot + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object o) {
        return (List<T>) o;
    }
}