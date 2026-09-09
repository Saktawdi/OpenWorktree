package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * 存储设置端点（T-116，设置中心「存储设置」页）：概览 / 缓存清单 / 按类清理 /
 * 「在系统中打开」的真实 HTTP 契约。目录打开动作由 StorageInfoService 的默认启动器
 * 交给 OS——CI 环境无桌面时 open 会失败，因此 open 的行为断言只覆盖 401 鉴权与
 * 未知 target 的 400；真实拉起逻辑由 StorageInfoServiceTest 的注入缝覆盖。
 */
@Tag("slow")
class StorageRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void routesAreMountedBehindAuth() throws Exception {
        HttpResponse<String> anon = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/storage/overview")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anon.statusCode());
    }

    @Test
    void overviewEmitsConfiguredDirs() throws Exception {
        HttpResponse<String> res = get("/api/storage/overview");
        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = JSON.readValue(res.body(), Map.class);
        assertTrue(body.containsKey("toml_path"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) body.get("dirs");
        assertEquals(5, dirs.size(), "gate_home/clones/db/blob/audit 五个位置");
        for (Map<String, Object> d : dirs) {
            assertTrue(d.containsKey("key"));
            assertTrue(d.containsKey("path"));
            assertTrue(d.containsKey("bytes"));
            assertTrue(d.containsKey("exists"));
            assertTrue(d.containsKey("openable"));
        }
    }

    @Test
    void cachesListAndCleanRoundTrip() throws Exception {
        HttpResponse<String> list = get("/api/storage/caches");
        assertEquals(200, list.statusCode(), list.body());
        Map<String, Object> body = JSON.readValue(list.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> caches = (List<Map<String, Object>>) body.get("caches");
        assertEquals(3, caches.size(), "proc_temp / gate_tmp / adapters_log");
        for (Map<String, Object> c : caches) {
            assertTrue(c.containsKey("id"));
            assertTrue(c.containsKey("path"));
            assertTrue(c.containsKey("bytes"));
            assertTrue(c.containsKey("files"));
        }

        // 清理一个不存在的类别目录：ok 且计数为 0（幂等 no-op）
        HttpResponse<String> clean = post("/api/storage/caches/proc_temp/clean", "{}");
        assertEquals(200, clean.statusCode(), clean.body());
        Map<String, Object> cleaned = JSON.readValue(clean.body(), Map.class);
        assertEquals(true, cleaned.get("ok"));
        assertEquals(0, ((Number) cleaned.get("removed_bytes")).longValue());
        assertEquals(0, ((Number) cleaned.get("removed_files")).longValue());

        HttpResponse<String> unknown = post("/api/storage/caches/nope/clean", "{}");
        assertEquals(400, unknown.statusCode(), unknown.body());
    }

    @Test
    void cleanActuallyDeletesSeededFiles() throws Exception {
        // WebHarness 的 gateHome 在临时根下；直接种进程临时日志文件验证真实删除
        Path gateHome = harness.root().resolve("gate-home");
        Path proc = gateHome.resolve("proc");
        Files.createDirectories(proc);
        Files.writeString(proc.resolve("proc-out-1.log"), "abcdef", StandardCharsets.UTF_8);

        HttpResponse<String> clean = post("/api/storage/caches/proc_temp/clean", "{}");
        assertEquals(200, clean.statusCode(), clean.body());
        Map<String, Object> cleaned = JSON.readValue(clean.body(), Map.class);
        assertEquals(6, ((Number) cleaned.get("removed_bytes")).longValue(), clean.body());
        assertEquals(1, ((Number) cleaned.get("removed_files")).longValue(), clean.body());
        assertTrue(Files.isDirectory(proc), "目录保留");
    }

    @Test
    void openRejectsUnknownTarget() throws Exception {
        HttpResponse<String> res = post("/api/storage/open", "{\"target\":\"db\"}");
        assertEquals(400, res.statusCode(), res.body());
    }
}
