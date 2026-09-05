package gate.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件系统端到端（真实 HTTP）：目录扫描列表、启停、reload 指纹、资产下发（免鉴权 + 防穿越）、
 * KV 数据面（Bearer 鉴权 + 权限声明 + 启用状态门槛）。
 */
@Tag("slow")
class PluginRoutesTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private Path pluginsDir;

    @BeforeEach
    void setUp() throws IOException {
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
        client = HttpClient.newHttpClient();
        pluginsDir = harness.components().config().gateHome().resolve("plugins");
        installPlugin("alpha", "[]");
        installPlugin("beta", "[\"kv\"]");
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

    private void installPlugin(String id, String permissions) throws IOException {
        Path p = pluginsDir.resolve(id);
        Files.createDirectories(p.resolve("dist"));
        Files.writeString(p.resolve("manifest.json"), """
                {
                  "id": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  "apiVersion": "1",
                  "entry": "dist/index.js",
                  "css": "dist/style.css",
                  "permissions": %s
                }
                """.formatted(id, id, permissions));
        Files.writeString(p.resolve("dist").resolve("index.js"), "export function activate(){}");
        Files.writeString(p.resolve("dist").resolve("style.css"), "/* style */");
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return client.send(req(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAnon(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void listPluginsOverHttpRequiresToken() throws Exception {
        assertEquals(401, getAnon("/api/plugins").statusCode());
        HttpResponse<String> res = get("/api/plugins");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"id\":\"alpha\""), res.body());
        assertTrue(res.body().contains("\"id\":\"beta\""), res.body());
        assertTrue(res.body().contains("\"cache_tag\":\""), res.body());
        // 未登记过启停状态：默认启用
        assertTrue(res.body().contains("\"enabled\":true"), res.body());
    }

    @Test
    void enableDisableTogglesAndGatesKv() throws Exception {
        assertEquals(200, post("/api/plugins/beta/disable").statusCode());
        HttpResponse<String> after = get("/api/plugins");
        assertTrue(after.body().contains("\"id\":\"beta\",\"name\":\"beta\""), after.body());
        assertTrue(after.body().contains("\"enabled\":false"), after.body());
        // 禁用后 KV 数据面拒绝
        HttpResponse<String> put = client.send(
                req("/api/plugin-capabilities/kv/beta/quotes")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("[]", StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, put.statusCode(), put.body());
        assertTrue(put.body().contains("disabled"), put.body());
        // 重新启用恢复
        assertEquals(200, post("/api/plugins/beta/enable").statusCode());
        assertTrue(get("/api/plugins").body().contains("\"enabled\":true"));
    }

    @Test
    void reloadReturnsFreshCacheTag() throws Exception {
        HttpResponse<String> res = post("/api/plugins/alpha/reload");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"cache_tag\":\""), res.body());
        // 未知插件 reload → 400
        assertEquals(400, post("/api/plugins/ghost/reload").statusCode());
    }

    @Test
    void kvCrudRoundtripAndAuth() throws Exception {
        String path = "/api/plugin-capabilities/kv/beta/quotes";
        // 未带 token → 401（AuthFilter 覆盖 /api/plugin-capabilities/*）
        assertEquals(401, client.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        // PUT 对象值
        HttpResponse<String> put = client.send(
                req(path).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("[{\"id\":1,\"label\":\"预提审\"}]",
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode(), put.body());
        // GET 读回
        HttpResponse<String> got = get(path);
        assertEquals(200, got.statusCode(), got.body());
        assertTrue(got.body().contains("预提审"), got.body());
        // 缺失 key → 404
        assertEquals(404, get("/api/plugin-capabilities/kv/beta/nope").statusCode());
        // 非法 JSON → 400
        HttpResponse<String> bad = client.send(
                req(path).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{not json", StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode(), bad.body());
        // DELETE
        assertEquals(200, client.send(req(path).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, get(path).statusCode());
    }

    @Test
    void kvDeniedWithoutDeclaredPermission() throws Exception {
        HttpResponse<String> res = client.send(
                req("/api/plugin-capabilities/kv/alpha/key")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("1", StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("kv"), res.body());
    }

    @Test
    void assetsServeWithoutAuthAndBlockTraversal() throws Exception {
        // ES import 无法带 Authorization 头：资产端点按设计免鉴权（本机回环 + 路径规范化）
        HttpResponse<String> js = getAnon("/plugins/alpha/dist/index.js");
        assertEquals(200, js.statusCode(), js.body());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("text/javascript"));
        assertEquals("no-store", js.headers().firstValue("Cache-Control").orElse(""));

        HttpResponse<String> css = getAnon("/plugins/alpha/dist/style.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Content-Type").orElse("").contains("text/css"));

        // 未知资产 → 404
        assertEquals(404, getAnon("/plugins/alpha/dist/missing.js").statusCode());
        assertEquals(404, getAnon("/plugins/ghost/dist/index.js").statusCode());
        // 路径穿越（编码后的 ../）→ 拒绝（Jetty 可能直接以 400 拒掉歧义 URI，或由控制器 404）
        String traversal = "/plugins/alpha/dist/" + URLEncoder.encode("../../manifest.json", StandardCharsets.UTF_8);
        int traversalStatus = getAnon(traversal).statusCode();
        assertTrue(traversalStatus == 404 || traversalStatus == 400,
                "traversal must not be served, got " + traversalStatus);
    }
}
