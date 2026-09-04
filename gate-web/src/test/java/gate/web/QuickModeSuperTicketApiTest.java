package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V19 快速模式（项目超级工单）：每个项目恰有一条系统创建的常驻工单——直接操作项目原工作区
 * （clone_path 即 workspace_path，不克隆），永不关闭（PATCH 流转被拒），不走门禁
 * （presubmit / sync-base 均被拒），提交通过普通 git 直达主分支。
 */
class QuickModeSuperTicketApiTest {

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
    void project_provisions_exactly_one_permanent_super_ticket() throws Exception {
        Path ws = harness.root().resolve("qm-ws");
        Files.createDirectories(ws);
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"QM\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());

        // Project JSON exposes the super ticket for the console's 打开工作台 shortcut.
        assertTrue(created.body().contains("\"super_ticket_no\":\"T-"), created.body());
        String superNo = extract(created.body(), "super_ticket_no");

        HttpResponse<String> detail = get("/api/tickets/" + superNo);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"is_super\":true"), detail.body());
        assertTrue(detail.body().contains("\"stage\":\"IN_PROGRESS\""), detail.body());
        assertTrue(detail.body().contains(json(ws.toString())), "clone_path must be the workspace: " + detail.body());

        // The listing never duplicates the provision (idempotent ensure).
        HttpResponse<String> again = get("/api/projects");
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"super_ticket_no\":\"" + superNo + "\""), again.body());
        assertTrue(again.body().contains("\"ticket_count\":0"), "super ticket is not a work item: " + again.body());

        HttpResponse<String> tickets = get("/api/tickets");
        assertEquals(200, tickets.statusCode(), tickets.body());
        assertTrue(countOccurrences(tickets.body(), "\"is_super\":true") == 1, tickets.body());
    }

    @Test
    void super_ticket_never_changes_stage_or_enters_the_gate() throws Exception {
        Path ws = harness.root().resolve("qm-gate-ws");
        Files.createDirectories(ws);
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"QMG\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String superNo = extract(created.body(), "super_ticket_no");

        // No stage lever at all — never completes, never cancels, never reopens.
        HttpResponse<String> done = patch("/api/tickets/" + superNo,
                "{\"stage\":\"DONE\",\"reason\":\"尝试收尾\"}");
        assertEquals(400, done.statusCode(), done.body());
        assertTrue(done.body().contains("never closes"), done.body());

        HttpResponse<String> cancel = patch("/api/tickets/" + superNo,
                "{\"stage\":\"CANCELLED\",\"reason\":\"尝试取消\"}");
        assertEquals(400, cancel.statusCode(), cancel.body());

        HttpResponse<String> start = patch("/api/tickets/" + superNo, "{\"stage\":\"PENDING\"}");
        assertEquals(400, start.statusCode(), start.body());

        // The gate pipeline does not apply: presubmit and base sync are refused outright.
        HttpResponse<String> presubmit = post("/api/tickets/" + superNo + "/presubmit", "{}");
        assertEquals(400, presubmit.statusCode(), presubmit.body());
        assertTrue(presubmit.body().contains("gate pipeline"), presubmit.body());

        HttpResponse<String> sync = post("/api/tickets/" + superNo + "/sync-base", "{}");
        assertEquals(400, sync.statusCode(), sync.body());

        // Publishing is enqueued asynchronously (202); the worker still refuses the super ticket.
        HttpResponse<String> publish = post("/api/tickets/" + superNo + "/publish", "{\"round\":1}");
        assertEquals(202, publish.statusCode(), publish.body());
        String taskId = extract(publish.body(), "task_id");
        String taskStatus = pollTaskUntilTerminal(taskId);
        assertEquals("FAILED", taskStatus, "publishing a super ticket must fail in the worker");
        HttpResponse<String> failedTask = get("/api/tasks/" + taskId);
        assertTrue(failedTask.body().contains("gate pipeline"),
                "worker error must name the quick-mode rule: " + failedTask.body());
    }

    /** Publish runs on the TaskRunner worker; poll until it reaches DONE/FAILED (≤10s). */
    private String pollTaskUntilTerminal(String taskId) throws Exception {
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> res = get("/api/tasks/" + taskId);
            if (res.body().contains("\"status\":\"DONE\"")) {
                return "DONE";
            }
            if (res.body().contains("\"status\":\"FAILED\"")) {
                return "FAILED";
            }
            Thread.sleep(200);
        }
        throw new AssertionError("task did not reach a terminal state in time: " + taskId);
    }

    @Test
    void super_ticket_follows_workspace_and_base_moves() throws Exception {
        Path ws = harness.root().resolve("qm-move-ws");
        Files.createDirectories(ws);
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"QMM\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String superNo = extract(created.body(), "super_ticket_no");

        Path ws2 = harness.root().resolve("qm-move-ws-2");
        Files.createDirectories(ws2);
        HttpResponse<String> moved = put("/api/projects/qmm",
                "{\"workspace_path\":\"" + json(ws2.toString()) + "\",\"target_branch\":\"master\"}");
        assertEquals(200, moved.statusCode(), moved.body());

        HttpResponse<String> detail = get("/api/tickets/" + superNo);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains(json(ws2.toString())), detail.body());
        assertTrue(detail.body().contains("\"target_ref\":\"refs/heads/master\""), detail.body());
    }

    /* ── helpers (mirror TicketMetaApiTest) ── */

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\");
    }

    private static String extract(String jsonBody, String field) {
        int idx = jsonBody.indexOf("\"" + field + "\":\"");
        if (idx < 0) {
            throw new AssertionError("field not found: " + field + " in " + jsonBody);
        }
        int start = idx + field.length() + 4;
        int end = jsonBody.indexOf("\"", start);
        return jsonBody.substring(start, end);
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
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
