package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gate.web.service.ModelCatalogService;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 智能匹配（{@code POST /api/opencode/models/match}）：把本机模型 id 对到线上模型目录的配置项，
 * 回填成 opencode 可直接落盘的模型配置。
 *
 * <p>目录经 {@code opencode.models.catalog.url} 系统属性指向本地 mock，测试不碰真实网络。
 * 用例刻意贴着中转网关的真实形态走：点号/大小写差异、{@code -api}/{@code -free} 式后缀、日期戳、
 * {@code org/} 与 {@code srv_xx:org/} 前缀，以及「同一个 id 多家都有、值还不一样」——这些正是
 * 逐字查表查不到、也是这个功能存在的理由。另有独立用例守住两条反向边界：认得出的端点只认自家的行，
 * 编造的 id 绝不被糊到任何一行上。
 */
@Tag("slow")
class OpenCodeModelMatchApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 迷你目录：deepseek 与 aihubmix 收录同名模型但给的值不同（1M vs 200K），用来断言选行确实按
     * baseURL 收窄；zhipuai 下再放一条 {@code status: deprecated} 的行确认它不会被优先选中。
     */
    private static final String CATALOG = """
            {
              "deepseek": {
                "id": "deepseek",
                "api": "https://api.deepseek.com",
                "models": {
                  "deepseek-v4-flash": {
                    "id": "deepseek-v4-flash",
                    "name": "DeepSeek V4 Flash",
                    "reasoning": true, "tool_call": true, "temperature": true, "attachment": true,
                    "modalities": { "input": ["text", "image"], "output": ["text"] },
                    "limit": { "context": 1000000, "output": 384000 }
                  },
                  "deepseek-v4-flash-0731": {
                    "id": "deepseek-v4-flash-0731",
                    "name": "DeepSeek V4 Flash 0731",
                    "reasoning": true, "tool_call": true, "temperature": false, "attachment": false,
                    "modalities": { "input": ["text"], "output": ["text"] },
                    "limit": { "context": 1000000, "output": 384000 }
                  }
                }
              },
              "aihubmix": {
                "id": "aihubmix",
                "api": "https://api.aihubmix.com",
                "models": {
                  "deepseek-v4.1-flash": {
                    "id": "deepseek-v4.1-flash",
                    "name": "DeepSeek V4.1 Flash (relay)",
                    "reasoning": true, "tool_call": true, "temperature": true, "attachment": false,
                    "modalities": { "input": ["text"] },
                    "limit": { "context": 200000, "output": 64000 }
                  },
                  "deepseek-v4-flash": {
                    "id": "deepseek-v4-flash",
                    "name": "DeepSeek V4 Flash (relay)",
                    "reasoning": true, "tool_call": true, "temperature": true, "attachment": false,
                    "modalities": { "input": ["text"] },
                    "limit": { "context": 200000, "output": 64000 }
                  }
                }
              },
              "zhipuai": {
                "id": "zhipuai",
                "api": "https://open.bigmodel.cn/api/paas/v4",
                "models": {
                  "glm-5.3-flash": {
                    "id": "glm-5.3-flash",
                    "name": "GLM-5.3 Flash",
                    "reasoning": true, "tool_call": true, "temperature": true, "attachment": true,
                    "modalities": { "input": ["text", "image"], "output": ["text"] },
                    "limit": { "context": 1000000, "output": 131072 }
                  },
                  "glm-5.2-old": {
                    "id": "glm-5.2-old",
                    "name": "GLM-5.2 (retired)",
                    "status": "deprecated",
                    "limit": { "context": 999, "output": 111 },
                    "modalities": { "input": ["text"], "output": ["text"] }
                  }
                }
              }
            }
            """;

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private com.sun.net.httpserver.HttpServer catalogServer;
    private volatile int catalogStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        catalogServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalogServer.createContext("/catalog.json", ex -> {
            byte[] body = CATALOG.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(catalogStatus, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        catalogServer.start();
        System.setProperty(ModelCatalogService.CATALOG_URL_PROPERTY,
                "http://127.0.0.1:" + catalogServer.getAddress().getPort() + "/catalog.json");

        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(ModelCatalogService.CATALOG_URL_PROPERTY);
        if (catalogServer != null) {
            catalogServer.stop(0);
        }
        if (server != null) {
            server.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void relay_id_variants_resolve_to_a_writable_opencode_config() throws Exception {
        // 自建/中转网关：主机名认不出，靠 id 归一化匹配。覆盖点号→连字符、大小写、后缀与命名空间前缀。
        Map<String, Object> res = match("https://my-relay.internal/v1", List.of(
                "deepseek-v4-flash", "GLM-5.3-Flash", "srv_abc123:zhipuai/glm-5.3-flash",
                "glm-5.3-flash-free"));
        assertTrue((Boolean) res.get("catalog_ok"), String.valueOf(res));

        Map<?, ?> matched = (Map<?, ?>) res.get("matched");
        assertEquals(4, matched.size(), String.valueOf(res));

        Map<?, ?> flash = (Map<?, ?>) matched.get("deepseek-v4-flash");
        assertEquals("deepseek-v4-flash", flash.get("matched_id"));
        Map<?, ?> cfg = (Map<?, ?>) flash.get("config");
        Map<?, ?> limit = (Map<?, ?>) cfg.get("limit");
        assertEquals(1000000L, num(limit.get("context")), String.valueOf(cfg));
        assertEquals(384000L, num(limit.get("output")), String.valueOf(cfg));
        assertEquals(List.of("text", "image"), ((Map<?, ?>) cfg.get("modalities")).get("input"));
        // 四个能力位必须显式写出（opencode 里缺键与 false 语义不同）。
        assertEquals(true, cfg.get("reasoning"));
        assertEquals(true, cfg.get("tool_call"));
        assertEquals(true, cfg.get("temperature"));
        assertEquals(true, cfg.get("attachment"));

        // 后缀 -free、大小写差异、srv_xx:org/ 前缀叠加，都落到同一个目录行上。
        assertEquals("glm-5.3-flash", ((Map<?, ?>) matched.get("GLM-5.3-Flash")).get("matched_id"));
        assertEquals("glm-5.3-flash",
                ((Map<?, ?>) matched.get("srv_abc123:zhipuai/glm-5.3-flash")).get("matched_id"));
        assertEquals("glm-5.3-flash", ((Map<?, ?>) matched.get("glm-5.3-flash-free")).get("matched_id"));
    }

    @Test
    void base_url_picks_that_providers_row_over_the_others() throws Exception {
        // 同一个 id 两家都有、值不同：认得出端点时取该家那一行（1M），而不是中转行（200K）。
        Map<?, ?> viaDeepseek = firstConfig(match("https://api.deepseek.com/v1", List.of("deepseek-v4-flash")));
        assertEquals(1000000L, num(((Map<?, ?>) viaDeepseek.get("limit")).get("context")));

        Map<?, ?> viaRelay = firstConfig(match("https://api.aihubmix.com/v1", List.of("deepseek-v4-flash")));
        assertEquals(200000L, num(((Map<?, ?>) viaRelay.get("limit")).get("context")));

        // 兜底：认不出端点时（自建网关）退回全库匹配，仍给出一份可用配置——此时按第一方优先，
        // 取的是 deepseek 自家那行（1M），而不是某个中转商的保守值。
        Map<String, Object> unknown = match("https://my-relay.internal/v1", List.of("deepseek-v4-flash"));
        assertEquals(1000000L,
                num(((Map<?, ?>) firstConfig(unknown).get("limit")).get("context")), String.valueOf(unknown));
        assertEquals(null, unknown.get("provider_hint"), String.valueOf(unknown));
        // 认得出时回显认成了哪一家，供界面提示。
        assertEquals("deepseek", match("https://api.deepseek.com/v1", List.of("deepseek-v4-flash"))
                .get("provider_hint"));
    }

    @Test
    void known_endpoint_does_not_borrow_another_vendors_row() throws Exception {
        // glm-5.2-old 只在 zhipuai 名下：挂在 deepseek 端点上就是不该被匹配，
        // 而不是该去借 zhipuai（何况那行还标了 deprecated）的配置来填。
        Map<String, Object> res = match("https://api.deepseek.com/v1", List.of("glm-5.2-old", "deepseek-v4-flash"));
        Map<?, ?> matched = (Map<?, ?>) res.get("matched");
        assertTrue(matched.containsKey("deepseek-v4-flash"), String.valueOf(res));
        assertFalse(matched.containsKey("glm-5.2-old"), String.valueOf(res));
        assertEquals(List.of("glm-5.2-old"), res.get("unmatched"), String.valueOf(res));
    }

    @Test
    void invented_ids_are_reported_unmatched_never_guessed() throws Exception {
        Map<String, Object> res = match("https://my-relay.internal/v1", List.of(
                "deepseek-v4-flash", "totally-made-up-model", "gpt-9-ultra-nonexistent"));
        Map<?, ?> matched = (Map<?, ?>) res.get("matched");
        assertEquals(1, matched.size(), String.valueOf(res));
        assertEquals(List.of("totally-made-up-model", "gpt-9-ultra-nonexistent"), res.get("unmatched"),
                String.valueOf(res));
    }

    @Test
    void unreachable_catalog_reports_failure_instead_of_throwing() throws Exception {
        catalogStatus = 503;
        Map<String, Object> res = match("https://api.deepseek.com/v1", List.of("deepseek-v4-flash"));
        assertEquals(false, res.get("catalog_ok"), String.valueOf(res));
        assertTrue(String.valueOf(res.get("catalog_error")).contains("503"), String.valueOf(res));
        // 拉不到目录不是错误路径的终点：前端据此提示，并保留用户原配置。
        assertEquals(List.of("deepseek-v4-flash"), res.get("unmatched"), String.valueOf(res));
        assertEquals(Map.of(), res.get("matched"), String.valueOf(res));
    }

    @Test
    void blank_and_duplicate_ids_are_ignored() throws Exception {
        Map<String, Object> res = match("https://api.deepseek.com/v1", List.of(
                "deepseek-v4-flash", "  ", "", "deepseek-v4-flash"));
        assertEquals(1, ((Map<?, ?>) res.get("matched")).size(), String.valueOf(res));
        assertEquals(List.of(), res.get("unmatched"), String.valueOf(res));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> match(String baseUrl, List<String> models) throws Exception {
        String body = JSON.writeValueAsString(Map.of("base_url", baseUrl, "models", models));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/opencode/models/match"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return (Map<String, Object>) JSON.readValue(res.body(), Map.class);
    }

    private static Map<?, ?> firstConfig(Map<String, Object> res) {
        Map<?, ?> matched = (Map<?, ?>) res.get("matched");
        assertEquals(1, matched.size(), String.valueOf(res));
        return (Map<?, ?>) ((Map<?, ?>) matched.values().iterator().next()).get("config");
    }

    /** JSON 往返后数字可能是 Integer 或 Long，按数值比较。 */
    private static long num(Object v) {
        return ((Number) v).longValue();
    }
}
