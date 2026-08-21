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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fail-Closed degradation when no review engine is configured (架构规范 I7; 产品文档异常场景表:
 * 引擎不可用 → NEEDS_HUMAN,不是 4xx 报错).
 *
 * <p>The harness wires {@code GateConfig} without {@code [engine]} (the out-of-the-box local mode),
 * so every {@code POST /api/tickets/{no}/review} exercises the manual adapter. The three states of
 * {@code body.human_pass}:
 * <ul>
 *   <li><b>absent</b> — nobody decided yet → verdict REQUIRES_HUMAN, ticket stage NEEDS_HUMAN,
 *       {@code review_result.degraded=true}, readable findings. The API must NOT answer 4xx.</li>
 *   <li><b>true</b> — human pass → PASS, stage READY_TO_PUBLISH.</li>
 *   <li><b>false</b> — human reject → REJECT, stage REJECTED.</li>
 * </ul>
 */
class NoEngineFailClosedReviewTest {

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
    void review_without_human_pass_degrades_fail_closed_to_needs_human() throws Exception {
        String no = "FC-1";
        createTicketWithChange(no);
        assertEquals(200, post("/api/tickets/" + no + "/presubmit", "").statusCode());

        // No human_pass in the body and no engine configured: accepted (202), never a 4xx usage
        // error — the round degrades to a human decision instead of guessing.
        String task = startReview(no, "{}");
        waitForStatus(task, "SUCCEEDED");
        String result = taskResultJson(task);
        assertTrue(result.contains("\"verdict\":\"REQUIRES_HUMAN\""), result);

        assertEquals("NEEDS_HUMAN", ticketStage(no));

        HttpResponse<String> reviewResult = get("/api/tickets/" + no + "/review-result");
        assertEquals(200, reviewResult.statusCode(), reviewResult.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) gate.application.MiniJson.parse(
                reviewResult.body().trim());
        assertEquals("REQUIRES_HUMAN", row.get("verdict"), reviewResult.body());
        assertEquals(Boolean.TRUE, row.get("degraded"), "undecided round must be recorded degraded");
        String findings = String.valueOf(row.get("findings"));
        assertTrue(findings.contains("审查引擎未配置"), findings);
        assertTrue(findings.contains("manual-undecided"), findings);
    }

    @Test
    void review_with_human_pass_true_is_pass_and_ready_to_publish() throws Exception {
        String no = "FC-2";
        createTicketWithChange(no);
        assertEquals(200, post("/api/tickets/" + no + "/presubmit", "").statusCode());

        String task = startReview(no, "{\"human_pass\":true}");
        waitForStatus(task, "SUCCEEDED");
        String result = taskResultJson(task);
        assertTrue(result.contains("\"verdict\":\"PASS\""), result);

        assertEquals("READY_TO_PUBLISH", ticketStage(no));

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) gate.application.MiniJson.parse(
                get("/api/tickets/" + no + "/review-result").body().trim());
        assertEquals(Boolean.FALSE, row.get("degraded"), "a decided human round is not degraded");
    }

    @Test
    void review_with_human_pass_false_is_reject_and_rejected() throws Exception {
        String no = "FC-3";
        createTicketWithChange(no);
        assertEquals(200, post("/api/tickets/" + no + "/presubmit", "").statusCode());

        String task = startReview(no, "{\"human_pass\":false,\"note\":\"not ready\"}");
        waitForStatus(task, "SUCCEEDED");
        String result = taskResultJson(task);
        assertTrue(result.contains("\"verdict\":\"REJECT\""), result);

        assertEquals("REJECTED", ticketStage(no));

        String findings = String.valueOf(rowOfReviewResult(no).get("findings"));
        assertTrue(findings.contains("not ready"), "human note becomes the BLOCKER finding: " + findings);
        assertFalse(findings.contains("审查引擎未配置"), findings);
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private void createTicketWithChange(String no) throws Exception {
        HttpResponse<String> created = post("/api/tickets",
                "{\"ticket_no\":\"" + no + "\",\"title\":\"fail-closed\"}");
        assertEquals(201, created.statusCode(), created.body());
        Path clone = harness.components().config().clonesRoot().resolve(no);
        Files.writeString(clone.resolve("feature.txt"), "no engine configured\n", StandardCharsets.UTF_8);
    }

    private String startReview(String no, String body) throws Exception {
        HttpResponse<String> res = post("/api/tickets/" + no + "/review", body);
        assertEquals(202, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(res.body().trim());
        return String.valueOf(m.get("task_id"));
    }

    private void waitForStatus(String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            HttpResponse<String> res = get("/api/tasks/" + taskId);
            assertEquals(200, res.statusCode(), res.body());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(res.body().trim());
            String status = String.valueOf(m.get("status"));
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                fail("task " + taskId + " failed unexpectedly: " + res.body());
            }
            Thread.sleep(50);
        }
        fail("timed out waiting for task " + taskId + " to reach " + expected);
    }

    @SuppressWarnings("unchecked")
    private String taskResultJson(String taskId) throws Exception {
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(
                get("/api/tasks/" + taskId).body().trim());
        return String.valueOf(m.get("result_json"));
    }

    private String ticketStage(String no) throws Exception {
        HttpResponse<String> res = get("/api/tickets/" + no);
        assertEquals(200, res.statusCode(), res.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(res.body().trim());
        return String.valueOf(m.get("stage"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowOfReviewResult(String no) throws Exception {
        return (Map<String, Object>) gate.application.MiniJson.parse(
                get("/api/tickets/" + no + "/review-result").body().trim());
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
