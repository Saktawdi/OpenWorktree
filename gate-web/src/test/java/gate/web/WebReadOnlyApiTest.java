package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.policy.Decision;
import gate.domain.review.EngineDescriptor;
import gate.web.util.HttpStatus;
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

/**
 * S1 acceptance A14 (执行文档-后端-web §9.5, §9.6): read-only + synchronous REST.
 *
 * <ul>
 *   <li>{@code GET /api/status} / {@code /api/tickets} / {@code /api/metrics} / {@code /api/metrics/h1}
 *       / {@code /api/config} / {@code /api/providers} return structured JSON</li>
 *   <li>{@code POST /api/tickets} creates the clone AND inserts the ticket (D3 clone takeover)</li>
 *   <li>{@code POST /api/tickets/{no}/presubmit}固化 tree synchronously; diff readable back</li>
 *   <li>{@code /api/config} is redacted (no api_key / base_url secret leakage)</li>
 * </ul>
 *
 * <p>All requests carry the HUMAN bearer token minted by {@link WebHarness}.
 */
class WebReadOnlyApiTest {

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
    void status_returns_structured_json() throws Exception {
        HttpResponse<String> res = get("/api/status");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"target_ref\":\"refs/heads/main\""), res.body());
        assertTrue(res.body().contains("\"tickets\":["), res.body());
    }

    @Test
    void tickets_list_is_empty_then_grows_after_create() throws Exception {
        HttpResponse<String> before = get("/api/tickets");
        assertEquals(200, before.statusCode());
        assertTrue(before.body().contains("\"tickets\":[]"), before.body());

        HttpResponse<String> created = post("/api/tickets", "{\"ticket_no\":\"WEB-1\",\"title\":\"first\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"clone_path\""), created.body());

        // Clone directory actually exists on disk (clone takeover, D3).
        Path clone = harness.components().config().clonesRoot().resolve("WEB-1");
        assertTrue(Files.isDirectory(clone.resolve(".git")), "clone must be created on disk: " + clone);

        HttpResponse<String> after = get("/api/tickets");
        assertTrue(after.body().contains("\"ticket_no\":\"WEB-1\""), after.body());

        HttpResponse<String> detail = get("/api/tickets/WEB-1");
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"stage\":\"IN_PROGRESS\""), detail.body());
    }

    @Test
    void presubmit_is_synchronous_and_diff_is_readable() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"WEB-2\",\"title\":\"t\"}");
        // Write a file into the clone so the tree differs from base.
        Path clone = harness.components().config().clonesRoot().resolve("WEB-2");
        Files.writeString(clone.resolve("feature.txt"), "hello from web\n");

        HttpResponse<String> pre = post("/api/tickets/WEB-2/presubmit", "");
        assertEquals(200, pre.statusCode(), pre.body());
        assertTrue(pre.body().contains("\"review_round\":1"), pre.body());
        assertTrue(pre.body().contains("\"tree_hash\""), pre.body());

        HttpResponse<String> diff = get("/api/tickets/WEB-2/presubmit/1/diff");
        assertEquals(200, diff.statusCode(), diff.body());
        assertTrue(diff.body().contains("feature.txt"), diff.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void presubmit_history_lists_every_round_with_changed_count() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"WEB-PRE\",\"title\":\"rounds\"}");
        Path clone = harness.components().config().clonesRoot().resolve("WEB-PRE");

        // No round captured yet: an empty list, never an error (console renders "no history").
        HttpResponse<String> empty = get("/api/tickets/WEB-PRE/presubmits");
        assertEquals(200, empty.statusCode(), empty.body());
        Map<String, Object> emptyBody = (Map<String, Object>) gate.application.util.MiniJson.parse(
                empty.body().trim());
        assertEquals("WEB-PRE", emptyBody.get("ticket_no"));
        assertTrue(((List<?>) emptyBody.get("presubmits")).isEmpty(), empty.body());

        // Round 1: one changed file.
        Files.writeString(clone.resolve("first.txt"), "round one\n");
        assertEquals(200, post("/api/tickets/WEB-PRE/presubmit", "").statusCode());
        // Round 2: a second file lands on top — the working-tree diff now covers both.
        Files.writeString(clone.resolve("second.txt"), "round two\n");
        HttpResponse<String> round2 = post("/api/tickets/WEB-PRE/presubmit", "");
        assertEquals(200, round2.statusCode(), round2.body());

        HttpResponse<String> list = get("/api/tickets/WEB-PRE/presubmits");
        assertEquals(200, list.statusCode(), list.body());
        Map<String, Object> body = (Map<String, Object>) gate.application.util.MiniJson.parse(
                list.body().trim());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("presubmits");
        assertEquals(2, rows.size(), list.body());
        // MiniJson parses integers as Long — compare through Number.
        assertEquals(1L, ((Number) rows.get(0).get("review_round")).longValue(), list.body());
        assertEquals(2L, ((Number) rows.get(1).get("review_round")).longValue(), list.body());
        assertEquals(1L, ((Number) rows.get(0).get("changed_count")).longValue(),
                "round 1 touched one file: " + list.body());
        assertEquals(2L, ((Number) rows.get(1).get("changed_count")).longValue(),
                "round 2 covers both files: " + list.body());
        // 工单级目标分支（per-ticket branch）：工单创建即锚定 refs/heads/<工单号>，presubmit 记录同源。
        assertEquals("refs/heads/WEB-PRE", rows.get(0).get("target_ref"), list.body());
        assertTrue(((Number) rows.get(0).get("diff_bytes")).longValue() > 0, list.body());
        assertTrue(String.valueOf(rows.get(0).get("tree_hash")).matches("[0-9a-f]{40}"), list.body());
        assertTrue(String.valueOf(rows.get(0).get("created_at")).startsWith("2"), list.body());

        // Unknown ticket is a usage error, not an empty 200.
        HttpResponse<String> unknown = get("/api/tickets/NOPE-404/presubmits");
        assertTrue(unknown.statusCode() >= 400, unknown.body());
    }

    @Test
    void metrics_and_h1_return_structured_json() throws Exception {
        HttpResponse<String> metrics = get("/api/metrics");
        assertEquals(200, metrics.statusCode(), metrics.body());
        assertTrue(metrics.body().contains("\"records\":["), metrics.body());

        HttpResponse<String> h1 = get("/api/metrics/h1");
        assertEquals(200, h1.statusCode(), h1.body());
        assertTrue(h1.body().contains("\"classification\""), h1.body());
        assertTrue(h1.body().contains("\"sample_count\""), h1.body());
    }

    @Test
    void config_is_redacted_and_providers_listed() throws Exception {
        HttpResponse<String> cfg = get("/api/config");
        assertEquals(200, cfg.statusCode(), cfg.body());
        assertTrue(cfg.body().contains("\"project\":\"web-test\""), cfg.body());
        assertTrue(!cfg.body().contains("api_key"), "config must not leak api_key: " + cfg.body());

        HttpResponse<String> providers = get("/api/providers");
        assertEquals(200, providers.statusCode(), providers.body());
        assertTrue(providers.body().contains("\"providers\":["), providers.body());
        assertTrue(providers.body().contains("\"manual\""), providers.body());
    }

    @Test
    void reconcile_returns_outcomes() throws Exception {
        HttpResponse<String> res = post("/api/reconcile", "");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"outcomes\":["), res.body());
    }

    @Test
    void review_result_endpoint_returns_stored_findings() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"WEB-4\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("WEB-4");
        Files.writeString(clone.resolve("feature.txt"), "change\n");
        HttpResponse<String> pre = post("/api/tickets/WEB-4/presubmit", "");
        assertEquals(200, pre.statusCode(), pre.body());

        // The review flow itself is async (S2); here we seed a review_result row directly through the
        // repositories so the S1 read-only endpoint has a latest finding to return (reject feedback).
        WebComponents c = harness.components();
        var presubmitRow = c.presubmitRepository().findLatest("WEB-4").orElseThrow();
        var findings = c.blobStore().put(
                "BLOCKER: fix the thing\n".getBytes(StandardCharsets.UTF_8), "review/WEB-4/1/findings.txt");
        var raw = c.blobStore().put("{}".getBytes(StandardCharsets.UTF_8), "review/WEB-4/1/raw.json");
        var engine = new EngineDescriptor("manual", "1", "manual", "manual", "manual");
        c.reviewResultRepository().insert(presubmitRow.id(), engine, Decision.Verdict.REJECT,
                findings, false, false, raw, c.clock().now());

        HttpResponse<String> rr = get("/api/tickets/WEB-4/review-result");
        assertEquals(200, rr.statusCode(), rr.body());
        assertTrue(rr.body().contains("\"verdict\":\"REJECT\""), rr.body());
        assertTrue(rr.body().contains("\"review_round\":1"), rr.body());
        assertTrue(rr.body().contains("BLOCKER: fix the thing"), rr.body());
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
