package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S2 acceptance A15/A16 (执行文档-后端-web §9.1, §9.5): the Web gate is equivalent to the MCP/CLI
 * gate — a full happy path through the HTTP console moves the authoritative HEAD by exactly one
 * commit, and bypass attempts fail without moving it.
 */
class WebGateEquivalenceTest {

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
        // For SoD, ensure the human token has REVIEWER and PUBLISHER via separate assignment for this test's user
        // The harness now seeds publisherToken separately; we will use it for publish to avoid SoD
        try {
            String userId = "human:" + token.substring(0, Math.min(8, token.length()));
            harness.components().rbacPort().assignRole(userId, "default", null, gate.domain.security.RbacRole.REVIEWER, "test");
            harness.components().rbacPort().assignRole(userId, "default", null, gate.domain.security.RbacRole.PUBLISHER, "test");
            // Seed sod_exception for EQ tickets to allow same-user review+publish in happy path
            for (String no : List.of("EQ-1", "EQ-2", "EQ-3")) {
                for (int round : List.of(1)) {
                    String excId = java.util.UUID.randomUUID().toString();
                    try { harness.components().jdbc().update("INSERT OR IGNORE INTO sod_exception(id,ticket_no,review_round,requester_user_id,approver_user_id,reason,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?)",
                            excId, no, round, userId, "admin", "test sod bypass", java.time.Instant.now().plusSeconds(3600).toString(), java.time.Instant.now().toString()); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
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
    void happy_path_review_then_publish_advances_auth_by_one_commit() throws Exception {
        String before = git("rev-parse", "HEAD").trim();

        String no = "EQ-1";
        createTicketWithChange(no);
        assertEquals(200, post("/api/tickets/" + no + "/presubmit", "").statusCode());

        String reviewTask = startTask("POST", "/api/tickets/" + no + "/review",
                "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");
        String reviewResult = taskResultJson(reviewTask);
        assertTrue(reviewResult.contains("\"verdict\":\"PASS\""), reviewResult);

        String publishTask = startTask("POST", "/api/tickets/" + no + "/publish", "{}");
        waitForStatus(publishTask, "SUCCEEDED");

        String after = git("rev-parse", "HEAD").trim();
        assertFalse(after.equals(before), "authoritative HEAD must move");
        String count = git("rev-list", "--count", before + ".." + after).trim();
        assertEquals("1", count, "authoritative HEAD must advance by exactly one commit");
    }

    @Test
    void publish_without_review_fails_and_does_not_move_auth() throws Exception {
        String before = git("rev-parse", "HEAD").trim();

        String no = "EQ-2";
        createTicketWithChange(no);
        assertEquals(200, post("/api/tickets/" + no + "/presubmit", "").statusCode());

        String publishTask = startTask("POST", "/api/tickets/" + no + "/publish", "{}");
        waitForStatus(publishTask, "FAILED");
        String errorJson = taskErrorJson(publishTask);
        // The existing GateService rejects an unreviewed publish with USAGE (64). The Web task
        // surfaces that exact gate error; the authoritative invariant is that HEAD does not move.
        assertTrue(errorJson.contains("\"error_code\":64"), errorJson);
        assertTrue(errorJson.contains("USAGE"), errorJson);

        assertEquals(before, git("rev-parse", "HEAD").trim(), "authoritative HEAD must not move");
    }

    @Test
    void concurrent_publish_does_not_duplicate_commit() throws Exception {
        String before = git("rev-parse", "HEAD").trim();

        String no = "EQ-3";
        createTicketWithChange(no);
        assertEquals(200, post("/api/tickets/" + no + "/presubmit", "").statusCode());
        String reviewTask = startTask("POST", "/api/tickets/" + no + "/review",
                "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");

        String t1 = startTask("POST", "/api/tickets/" + no + "/publish", "{}");
        String t2 = startTask("POST", "/api/tickets/" + no + "/publish", "{}");
        waitForStatus(t1, "SUCCEEDED");
        waitForStatus(t2, "SUCCEEDED");

        String after = git("rev-parse", "HEAD").trim();
        assertFalse(after.equals(before), "authoritative HEAD must move");
        String count = git("rev-list", "--count", before + ".." + after).trim();
        assertEquals("1", count, "concurrent publishes must not create a second commit");
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private void createTicketWithChange(String no) throws Exception {
        HttpResponse<String> created = post("/api/tickets",
                "{\"ticket_no\":\"" + no + "\",\"title\":\"eq\"}");
        assertEquals(201, created.statusCode(), created.body());
        Path clone = harness.components().config().clonesRoot().resolve(no);
        Files.writeString(clone.resolve("feature.txt"), "web equivalence\n", StandardCharsets.UTF_8);
    }

    private String startTask(String method, String path, String body) throws Exception {
        HttpResponse<String> res = post(path, body);
        assertEquals(202, res.statusCode(), res.body());
        return taskId(res.body());
    }

    private void waitForStatus(String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String lastBody = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> res = get("/api/tasks/" + taskId);
            assertEquals(200, res.statusCode(), res.body());
            lastBody = res.body();
            String status = taskStatus(res.body());
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status) && !"FAILED".equals(expected)) {
                fail("task " + taskId + " failed unexpectedly: " + res.body());
            }
            Thread.sleep(50);
        }
        fail("timed out waiting for task " + taskId + " to reach " + expected + " last=" + lastBody);
    }

    @SuppressWarnings("unchecked")
    private static String taskStatus(String body) {
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(body.trim());
        return String.valueOf(m.get("status"));
    }

    @SuppressWarnings("unchecked")
    private String taskResultJson(String taskId) throws Exception {
        String body = get("/api/tasks/" + taskId).body();
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(body.trim());
        return String.valueOf(m.get("result_json"));
    }

    @SuppressWarnings("unchecked")
    private String taskErrorJson(String taskId) throws Exception {
        String body = get("/api/tasks/" + taskId).body();
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(body.trim());
        return String.valueOf(m.get("error_json"));
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

    private String git(String... args) throws Exception {
        Path auth = harness.components().config().authRepo();
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = auth.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        assertEquals(0, code, "git " + String.join(" ", args) + " failed: " + out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String taskId(String responseBody) {
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(responseBody.trim());
        return String.valueOf(m.get("task_id"));
    }
}
