package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * 发布身份集成测试：默认提交作者 = 本机 git 作者（未配置则回退 gate），提交时间为发布时刻
 * 真实时间——不再是 [gate_identity] 钉死的 gate@localhost / 1700000000（T-110 后续反馈）。
 * 主题行同时验证"工单号 + 标题 + 轮次"格式。
 */
@Tag("slow")
class PublishIdentityApiTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private long testStartEpochSecond;

    @BeforeEach
    void setUp() {
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
        testStartEpochSecond = System.currentTimeMillis() / 1000 - 5;
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
    void published_commit_uses_local_git_author_real_time_and_title_subject() throws Exception {
        Path ws = harness.root().resolve("identity-ws");
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.must(ws, Map.of(), "init", "-b", "main");

        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"IdentityProj\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = extract(created.body(), "id");
        String authRepo = extract(created.body(), "auth_repo");

        git.must(ws, Map.of(), "fetch", authRepo, "main");
        git.must(ws, Map.of(), "reset", "--hard", "FETCH_HEAD");

        String ticketNo = "IDENT-1";
        HttpResponse<String> tCreated = post("/api/tickets",
                "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"identity\",\"project_id\":\"" + projectId
                        + "\",\"target_branch\":\"main\"}");
        assertEquals(201, tCreated.statusCode(), tCreated.body());

        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        Files.writeString(clone.resolve("identity.txt"), "author check\n", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(200, post("/api/tickets/" + ticketNo + "/presubmit", "").statusCode());

        String reviewTask = startTask("POST", "/api/tickets/" + ticketNo + "/review", "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");

        String publishTask = startTask("POST", "/api/tickets/" + ticketNo + "/publish", "{}");
        waitForStatus(publishTask, "SUCCEEDED");
        String commitSha = extract(taskResultJson(publishTask), "commit_sha");

        var auth = gate.domain.git.RepoRef.of(Path.of(authRepo));
        String expectedName = configOr(ws, "user.name", "gate");
        String expectedEmail = configOr(ws, "user.email", "gate@localhost");
        assertEquals(expectedName, git.line(auth, "show", "-s", "--format=%an", commitSha),
                "publish author must default to the local git author");
        assertEquals(expectedEmail, git.line(auth, "show", "-s", "--format=%ae", commitSha));
        assertEquals(expectedName, git.line(auth, "show", "-s", "--format=%cn", commitSha));

        long committerSecond = Long.parseLong(
                git.line(auth, "show", "-s", "--format=%ct", commitSha).trim());
        assertTrue(committerSecond >= testStartEpochSecond,
                "commit time must be the real publish instant, got " + committerSecond);

        // 提交主题 = 工单号 + 标题 + 轮次（T-110 后续反馈）。
        assertEquals("IDENT-1 identity (round 1)",
                git.line(auth, "show", "-s", "--format=%s", commitSha).trim(),
                "commit subject must be ticketNo + title + round");
    }

    /** provider 读的是 global/system 合成视角；测试用同一视角求期望值，未配置回退固定身份。 */
    private String configOr(Path cwd, String key, String fallback) {
        var run = harness.components().git().run(cwd, Map.of(), "config", "--get", key);
        String value = run.ok() ? run.stdout().trim() : "";
        return value.isBlank() ? fallback : value;
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private static String json(String value) {
        return value.replace("\\", "\\\\");
    }

    private static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\":\"");
        if (i < 0) {
            throw new AssertionError("field " + field + " missing in " + json);
        }
        int start = i + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }

    private String startTask(String method, String path, String body) throws Exception {
        HttpResponse<String> res = post(path, body);
        assertEquals(202, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(res.body().trim());
        return String.valueOf(m.get("task_id"));
    }

    private void waitForStatus(String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String body = get("/api/tasks/" + taskId).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(body.trim());
            String status = String.valueOf(m.get("status"));
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("task failed: " + body);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for task " + taskId);
    }

    private String taskResultJson(String taskId) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(
                get("/api/tasks/" + taskId).body().trim());
        return String.valueOf(m.get("result_json"));
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
