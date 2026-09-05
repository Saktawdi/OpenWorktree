package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.junit.jupiter.api.Tag;

/**
 * Integration tests for workspace sync after publish and POST /api/projects/{id}/workspace-sync.
 */
@Tag("slow")
class WorkspaceSyncApiTest {

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
    void publish_syncs_project_workspace() throws Exception {
        // 1. Create a workspace repository initialized with main branch
        Path ws = harness.root().resolve("sync-ws-1");
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.must(ws, Map.of(), "init", "-b", "main");
        git.must(ws, Map.of(), "config", "user.name", "sync-test");
        git.must(ws, Map.of(), "config", "user.email", "sync-test@localhost");

        // 2. Register project
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"SyncProj1\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = extract(created.body(), "id");
        String authRepo = extract(created.body(), "auth_repo");

        // Align workspace with auth repo base lineage
        git.must(ws, Map.of(), "fetch", authRepo, "main");
        git.must(ws, Map.of(), "reset", "--hard", "FETCH_HEAD");

        // 3. Create ticket belonging to project
        String ticketNo = "SYNC-1";
        HttpResponse<String> tCreated = post("/api/tickets",
                "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"sync task 1\",\"project_id\":\"" + projectId + "\",\"target_branch\":\"main\"}");
        assertEquals(201, tCreated.statusCode(), tCreated.body());

        // 4. Make change in clone
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        Files.writeString(clone.resolve("change.txt"), "ticket change 1\n", StandardCharsets.UTF_8);

        // 5. Presubmit
        assertEquals(200, post("/api/tickets/" + ticketNo + "/presubmit", "").statusCode());

        // 6. Review pass
        String reviewTask = startTask("POST", "/api/tickets/" + ticketNo + "/review", "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");
        String reviewResult = taskResultJson(reviewTask);
        assertTrue(reviewResult.contains("\"verdict\":\"PASS\""), reviewResult);

        // 7. Publish
        String publishTask = startTask("POST", "/api/tickets/" + ticketNo + "/publish", "{}");
        waitForStatus(publishTask, "SUCCEEDED");
        String publishResult = taskResultJson(publishTask);

        // Assert workspace_sync_status is SYNCED
        assertTrue(publishResult.contains("\"workspace_sync_status\":\"SYNCED\""), publishResult);

        // Extract commit_sha
        String commitSha = extract(publishResult, "commit_sha");
        String wsTip = git.line(gate.domain.git.RepoRef.of(ws), "rev-parse", "--verify", "refs/heads/main");
        assertEquals(commitSha, wsTip, "workspace tip must match published commit_sha");
    }

    @Test
    void workspace_sync_endpoint_repairs_stale_workspace() throws Exception {
        // 1. Create workspace
        Path ws = harness.root().resolve("sync-ws-2");
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.must(ws, Map.of(), "init", "-b", "main");
        git.must(ws, Map.of(), "config", "user.name", "sync-test");
        git.must(ws, Map.of(), "config", "user.email", "sync-test@localhost");

        // 2. Register project
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"SyncProj2\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = extract(created.body(), "id");
        String authRepo = extract(created.body(), "auth_repo");

        git.must(ws, Map.of(), "fetch", authRepo, "main");
        git.must(ws, Map.of(), "reset", "--hard", "FETCH_HEAD");
        String initialWsTip = git.line(gate.domain.git.RepoRef.of(ws), "rev-parse", "--verify", "refs/heads/main");

        // 3. Create ticket and publish it
        String ticketNo = "SYNC-2";
        HttpResponse<String> tCreated = post("/api/tickets",
                "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"sync task 2\",\"project_id\":\"" + projectId + "\",\"target_branch\":\"main\"}");
        assertEquals(201, tCreated.statusCode(), tCreated.body());

        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        Files.writeString(clone.resolve("feature.txt"), "feature 2\n", StandardCharsets.UTF_8);
        assertEquals(200, post("/api/tickets/" + ticketNo + "/presubmit", "").statusCode());

        String reviewTask = startTask("POST", "/api/tickets/" + ticketNo + "/review", "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");

        String publishTask = startTask("POST", "/api/tickets/" + ticketNo + "/publish", "{}");
        waitForStatus(publishTask, "SUCCEEDED");
        String publishResult = taskResultJson(publishTask);
        String publishedCommitSha = extract(publishResult, "commit_sha");

        // 4. Manually reset workspace back to initial tip to simulate stale workspace
        git.must(ws, Map.of(), "reset", "--hard", initialWsTip);
        assertEquals(initialWsTip, git.line(gate.domain.git.RepoRef.of(ws), "rev-parse", "--verify", "refs/heads/main"));

        // 5. Call POST /api/projects/{id}/workspace-sync
        HttpResponse<String> syncRes = post("/api/projects/" + projectId + "/workspace-sync", "{}");
        assertEquals(200, syncRes.statusCode(), syncRes.body());
        String body = syncRes.body();
        assertTrue(body.contains("\"status\":\"SYNCED\""), body);
        assertTrue(body.contains("\"auth_tip\":\"" + publishedCommitSha + "\""), body);
        assertTrue(body.contains("\"workspace_tip_before\":\"" + initialWsTip + "\""), body);
        assertTrue(body.contains("\"workspace_tip_after\":\"" + publishedCommitSha + "\""), body);

        // Verify workspace is updated
        String finalWsTip = git.line(gate.domain.git.RepoRef.of(ws), "rev-parse", "--verify", "refs/heads/main");
        assertEquals(publishedCommitSha, finalWsTip);
    }

    @Test
    void workspace_sync_endpoint_rejects_non_git_workspace() throws Exception {
        Path plain = Files.createDirectories(harness.root().resolve("non-git-ws"));
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"NonGitProj\",\"workspace_path\":\"" + json(plain.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = extract(created.body(), "id");

        HttpResponse<String> syncRes = post("/api/projects/" + projectId + "/workspace-sync", "{}");
        assertTrue(syncRes.statusCode() >= 400, "non-git workspace must be rejected: " + syncRes.body());
        assertTrue(syncRes.body().contains("not a git repository") || syncRes.body().contains("USAGE"), syncRes.body());
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
        return taskId(res.body());
    }

    private void waitForStatus(String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            HttpResponse<String> res = get("/api/tasks/" + taskId);
            assertEquals(200, res.statusCode(), res.body());
            String status = taskStatus(res.body());
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status) && !"FAILED".equals(expected)) {
                fail("task " + taskId + " failed unexpectedly: " + res.body());
            }
            Thread.sleep(50);
        }
        fail("timed out waiting for task " + taskId + " to reach " + expected);
    }

    @SuppressWarnings("unchecked")
    private static String taskId(String responseBody) {
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(responseBody.trim());
        return String.valueOf(m.get("task_id"));
    }

    @SuppressWarnings("unchecked")
    private static String taskStatus(String body) {
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(body.trim());
        return String.valueOf(m.get("status"));
    }

    @SuppressWarnings("unchecked")
    private String taskResultJson(String taskId) throws Exception {
        String body = get("/api/tasks/" + taskId).body();
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(body.trim());
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
