package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * 变更对比按需加载的 S1 验收：/diff/list 只含元数据（path/状态/±行数，零 diff 内容），
 * /diff/file 按需返回单文件 unified diff（含未跟踪文件的 new-file 物化与路径穿越拒绝）。
 *
 * <p>All requests carry the HUMAN bearer token minted by {@link WebHarness}.
 */
@Tag("slow")
class WorkingDiffApiTest {

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
    void diff_list_carries_metadata_without_diff_content() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"WEB-5\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("WEB-5");

        // 修改一个已跟踪文件（种子仓库里必然存在），再新增一个未跟踪文件。
        String tracked = git(clone, "ls-files").lines().filter(s -> !s.isBlank()).findFirst().orElseThrow();
        Files.writeString(clone.resolve(tracked), "appended line\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        Files.writeString(clone.resolve("fresh.txt"), "x\ny\n");

        HttpResponse<String> list = get("/api/tickets/WEB-5/diff/list");
        assertEquals(200, list.statusCode(), list.body());
        // 元数据在：两个文件、状态与行数齐备
        assertTrue(list.body().contains("\"path\":\"" + tracked + "\""), list.body());
        assertTrue(list.body().contains("\"status\":\"modified\""), list.body());
        assertTrue(list.body().contains("\"path\":\"fresh.txt\""), list.body());
        assertTrue(list.body().contains("\"status\":\"added\""), list.body());
        assertTrue(list.body().contains("\"additions\":2"), list.body());
        // 内容不在：unified diff 的标志行绝不出现
        assertTrue(!list.body().contains("diff --git"), "list must not carry diff content: " + list.body());
        assertTrue(!list.body().contains("@@"), "list must not carry hunks: " + list.body());
    }

    @Test
    void diff_file_returns_single_file_diff_on_demand() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"WEB-6\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("WEB-6");
        Files.writeString(clone.resolve("fresh.txt"), "x\ny\n");
        String tracked = git(clone, "ls-files").lines().filter(s -> !s.isBlank()).findFirst().orElseThrow();
        Files.writeString(clone.resolve(tracked), "appended line\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        // 未跟踪文件 → 物化 new-file diff
        HttpResponse<String> fresh = get("/api/tickets/WEB-6/diff/file?path=fresh.txt");
        assertEquals(200, fresh.statusCode(), fresh.body());
        assertTrue(fresh.body().contains("new file mode 100644"), fresh.body());
        assertTrue(fresh.body().contains("+x"), fresh.body());
        assertTrue(fresh.body().contains("+y"), fresh.body());

        // 已跟踪文件 → 常规 unified diff，且只含该文件
        HttpResponse<String> mod = get("/api/tickets/WEB-6/diff/file?path=" + tracked);
        assertEquals(200, mod.statusCode(), mod.body());
        assertTrue(mod.body().contains("+appended line"), mod.body());
        assertTrue(!mod.body().contains("diff --git a/fresh.txt"), "must be single-file: " + mod.body());
    }

    @Test
    void diff_file_rejects_path_traversal_and_missing_path() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"WEB-7\",\"title\":\"t\"}");
        assertTrue(get("/api/tickets/WEB-7/diff/file?path=../secret").statusCode() >= 400);
        assertTrue(get("/api/tickets/WEB-7/diff/file").statusCode() >= 400);
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
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
