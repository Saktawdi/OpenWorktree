package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 粘贴/拖入的非图片附件落盘端点（POST /api/tickets/{no}/chat-files）：文件写入
 * 工单克隆 .gate/chat-files/（.git/info/exclude 收敛），返回克隆内相对路径供前端
 * 插进消息正文；缺字段/坏 base64/未知工单一律 USAGE 拒绝。
 */
@Tag("slow")
class ChatFileUploadTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;

    @BeforeEach
    void setUp() {
        harness = new WebHarness("git", "127.0.0.1", null);
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
    void uploads_into_clone_and_returns_relative_path() throws Exception {
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"CF-1\",\"title\":\"files\"}")
                .statusCode());
        HttpResponse<String> up = post("/api/tickets/CF-1/chat-files", """
                {"filename":"vslm.cc.har","data_base64":"aGVsbG8="}
                """);
        assertEquals(201, up.statusCode(), up.body());
        assertTrue(up.body().startsWith("{\"path\":\".gate/chat-files/"), up.body());

        Path dir = harness.root().resolve("clones").resolve("CF-1").resolve(".gate/chat-files");
        List<Path> saved = listFiles(dir);
        assertEquals(1, saved.size(), dir.toString());
        assertEquals("hello", Files.readString(saved.get(0)));
        String name = saved.get(0).getFileName().toString();
        assertTrue(name.endsWith("-vslm.cc.har"), name);

        // .gate/ 已追进克隆本地 .git/info/exclude（不污染 git status）。
        String exclude = Files.readString(
                harness.root().resolve("clones").resolve("CF-1").resolve(".git/info/exclude"));
        assertTrue(exclude.contains(".gate/"), exclude);
    }

    @Test
    void sanitizes_traversal_and_unicode_filenames() throws Exception {
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"CF-2\",\"title\":\"files\"}")
                .statusCode());
        HttpResponse<String> up = post("/api/tickets/CF-2/chat-files",
                "{\"filename\":\"..\\\\..\\\\报告 v1.har\",\"data_base64\":\"aGVsbG8=\"}");
        assertEquals(201, up.statusCode(), up.body());

        Path dir = harness.root().resolve("clones").resolve("CF-2").resolve(".gate/chat-files");
        List<Path> saved = listFiles(dir);
        assertEquals(1, saved.size(), dir.toString());
        // 穿越段与分隔符被白名单替换为下划线，落点仍在 chat-files 目录内。
        assertEquals("hello", Files.readString(saved.get(0)));
        String name = saved.get(0).getFileName().toString();
        assertTrue(name.endsWith("-.._..____v1.har"), name);
    }

    @Test
    void validates_missing_fields_and_unknown_ticket() throws Exception {
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"CF-3\",\"title\":\"files\"}")
                .statusCode());
        assertTrue(post("/api/tickets/CF-1/chat-files", "{\"data_base64\":\"aGVsbG8=\"}")
                .statusCode() >= 400, "missing filename must be rejected");
        assertTrue(post("/api/tickets/CF-1/chat-files", "{\"filename\":\"a.har\"}")
                .statusCode() >= 400, "missing data_base64 must be rejected");
        assertTrue(post("/api/tickets/CF-1/chat-files",
                "{\"filename\":\"a.har\",\"data_base64\":\"!!!\"}").statusCode() >= 400,
                "bad base64 must be rejected");
        // CF-1 未建工单：未知工单一律 USAGE 拒绝。
        assertTrue(post("/api/tickets/NOPE-1/chat-files",
                "{\"filename\":\"a.har\",\"data_base64\":\"aGVsbG8=\"}").statusCode() >= 400);
    }

    private static List<Path> listFiles(Path dir) throws Exception {
        try (Stream<Path> list = Files.list(dir)) {
            return list.toList();
        }
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
