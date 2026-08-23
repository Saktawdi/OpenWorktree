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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 工单级目标分支（T-110 后续反馈）：默认 {@code refs/heads/<工单号>}，可自定义短名，
 * 创建后锁定。一工单一分支：克隆、预提审、发布与工作区同步全部锚定该分支，主分支不动。
 */
class TicketBranchApiTest {

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
    void default_branch_is_ticket_no_and_publish_leaves_main_untouched() throws Exception {
        var created = newProjectWithWorkspace("BranchProj1", "branch-ws-1");
        String projectId = created[0];
        String authRepo = created[1];

        String ticketNo = "BR-1";
        HttpResponse<String> t = post("/api/tickets",
                "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"branch default\",\"project_id\":\"" + projectId + "\"}");
        assertEquals(201, t.statusCode(), t.body());
        assertTrue(t.body().contains("\"target_ref\":\"refs/heads/" + ticketNo + "\""), t.body());

        var git = harness.components().git();
        var auth = gate.domain.git.RepoRef.of(Path.of(authRepo));
        String mainTip = git.line(auth, "rev-parse", "--verify", "refs/heads/main");
        String branchTip = git.line(auth, "rev-parse", "--verify", "refs/heads/" + ticketNo);
        assertEquals(mainTip, branchTip, "ticket branch must be seeded at the primary tip");

        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        String cloneBranch = git.line(gate.domain.git.RepoRef.of(clone),
                "rev-parse", "--abbrev-ref", "HEAD").trim();
        assertEquals(ticketNo, cloneBranch, "clone must check out the ticket branch");

        // 完整发布链路落到工单分支，main 保持不动。
        Files.writeString(clone.resolve("branch.txt"), "per-ticket branch\n", StandardCharsets.UTF_8);
        assertEquals(200, post("/api/tickets/" + ticketNo + "/presubmit", "").statusCode());
        String reviewTask = startTask("POST", "/api/tickets/" + ticketNo + "/review", "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");
        String publishTask = startTask("POST", "/api/tickets/" + ticketNo + "/publish", "{}");
        waitForStatus(publishTask, "SUCCEEDED");
        String publishResult = taskResultJson(publishTask);
        String commitSha = extract(publishResult, "commit_sha");

        assertEquals(commitSha, git.line(auth, "rev-parse", "--verify", "refs/heads/" + ticketNo),
                "publish must land on the ticket branch");
        assertEquals(mainTip, git.line(auth, "rev-parse", "--verify", "refs/heads/main"),
                "primary main must stay untouched");
        assertTrue(publishResult.contains("\"target_ref\":\"refs/heads/" + ticketNo + "\""), publishResult);

        // 工作区同步跟随工单分支。
        Path ws = Path.of(created[2]);
        assertEquals(commitSha, git.line(gate.domain.git.RepoRef.of(ws),
                "rev-parse", "--verify", "refs/heads/" + ticketNo),
                "workspace sync must advance the ticket branch in the workspace repo");
    }

    @Test
    void custom_branch_name_roundtrips() throws Exception {
        var created = newProjectWithWorkspace("BranchProj2", "branch-ws-2");
        String projectId = created[0];

        HttpResponse<String> t = post("/api/tickets",
                "{\"ticket_no\":\"BR-2\",\"title\":\"custom\",\"project_id\":\"" + projectId
                        + "\",\"target_branch\":\"fix-docs-sync\"}");
        assertEquals(201, t.statusCode(), t.body());
        assertTrue(t.body().contains("\"target_ref\":\"refs/heads/fix-docs-sync\""), t.body());

        // 完整 ref 形式也接受并归一。
        HttpResponse<String> t2 = post("/api/tickets",
                "{\"ticket_no\":\"BR-3\",\"title\":\"full ref\",\"project_id\":\"" + projectId
                        + "\",\"target_branch\":\"refs/heads/feature-full\"}");
        assertEquals(201, t2.statusCode(), t2.body());
        assertTrue(t2.body().contains("\"target_ref\":\"refs/heads/feature-full\""), t2.body());
    }

    @Test
    void invalid_branch_names_are_rejected() throws Exception {
        HttpResponse<String> t = post("/api/tickets",
                "{\"ticket_no\":\"BR-BAD\",\"title\":\"bad\",\"target_branch\":\"feat/with/slash\"}");
        assertTrue(t.statusCode() >= 400, "slash branch must be rejected: " + t.body());
        assertTrue(t.body().contains("target_branch"), t.body());

        HttpResponse<String> t2 = post("/api/tickets",
                "{\"ticket_no\":\"BR-BAD2\",\"title\":\"bad\",\"target_branch\":\"..\"}");
        assertTrue(t2.statusCode() >= 400, t2.body());
    }

    @Test
    void target_ref_is_locked_after_creation() throws Exception {
        HttpResponse<String> t = post("/api/tickets",
                "{\"ticket_no\":\"BR-LOCK\",\"title\":\"lock\",\"target_branch\":\"locked-branch\"}");
        assertEquals(201, t.statusCode(), t.body());

        HttpResponse<String> patch = patchJson("/api/tickets/BR-LOCK",
                "{\"target_ref\":\"refs/heads/main\"}");
        assertTrue(patch.statusCode() >= 400, "target_ref must be locked: " + patch.body());
        assertTrue(patch.body().contains("locked"), patch.body());

        HttpResponse<String> patch2 = patchJson("/api/tickets/BR-LOCK",
                "{\"target_branch\":\"other\"}");
        assertTrue(patch2.statusCode() >= 400, patch2.body());
    }

    @Test
    void main_explicitly_keeps_shared_branch_semantics() throws Exception {
        HttpResponse<String> t = post("/api/tickets",
                "{\"ticket_no\":\"BR-MAIN\",\"title\":\"legacy\",\"target_branch\":\"main\"}");
        assertEquals(201, t.statusCode(), t.body());
        assertTrue(t.body().contains("\"target_ref\":\"refs/heads/main\""), t.body());
        // main 分支已存在，无需建支；克隆直接落在 main。
        Path clone = harness.components().config().clonesRoot().resolve("BR-MAIN");
        assertEquals("main", harness.components().git().line(
                gate.domain.git.RepoRef.of(clone), "rev-parse", "--abbrev-ref", "HEAD").trim());
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /** 返回 [projectId, authRepo, workspacePath]，workspace 已与 auth 基线对齐。 */
    private String[] newProjectWithWorkspace(String name, String wsDir) throws Exception {
        Path ws = harness.root().resolve(wsDir);
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.must(ws, Map.of(), "init", "-b", "main");
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"" + name + "\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = extract(created.body(), "id");
        String authRepo = extract(created.body(), "auth_repo");
        git.must(ws, Map.of(), "fetch", authRepo, "main");
        git.must(ws, Map.of(), "reset", "--hard", "FETCH_HEAD");
        return new String[] {projectId, authRepo, ws.toString()};
    }

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
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(res.body().trim());
        return String.valueOf(m.get("task_id"));
    }

    private void waitForStatus(String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String body = get("/api/tasks/" + taskId).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(body.trim());
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
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(
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

    private HttpResponse<String> patchJson(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
