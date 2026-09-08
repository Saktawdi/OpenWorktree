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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * Ticket metadata (web console): priority + project tagging on create, editable content, the PATCH
 * edit endpoint, queue-stage guard rails, and the live working-tree diff that replaces the UI's
 * sample diff.
 */
@Tag("slow")
class TicketMetaApiTest {

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
    void create_carries_priority_and_project_tag() throws Exception {
        String projectId = createProject("Alpha", "alpha-ws");
        HttpResponse<String> created = post("/api/tickets", "{\"ticket_no\":\"META-1\",\"title\":\"t\","
                + "\"priority\":\"P1\",\"project_id\":\"" + projectId + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"priority\":\"P1\""), created.body());

        HttpResponse<String> detail = get("/api/tickets/META-1");
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"priority\":\"P1\""), detail.body());
        assertTrue(detail.body().contains("\"project_id\":\"" + projectId + "\""), detail.body());
        assertTrue(detail.body().contains("\"project\":\"Alpha\""), detail.body());
    }

    @Test
    void invalid_priority_and_unknown_project_are_rejected() throws Exception {
        HttpResponse<String> badPriority = post("/api/tickets",
                "{\"ticket_no\":\"META-2\",\"title\":\"t\",\"priority\":\"PX\"}");
        assertTrue(badPriority.statusCode() >= 400, "priority PX must be rejected: " + badPriority.body());

        HttpResponse<String> badProject = post("/api/tickets",
                "{\"ticket_no\":\"META-3\",\"title\":\"t\",\"project_id\":\"nope\"}");
        assertTrue(badProject.statusCode() >= 400, "unknown project must be rejected: " + badProject.body());
    }

    @Test
    void patch_updates_title_and_clears_priority() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"META-4\",\"title\":\"old\",\"priority\":\"P0\"}");
        HttpResponse<String> patched = patch("/api/tickets/META-4",
                "{\"title\":\"new\",\"priority\":null}");
        assertEquals(200, patched.statusCode(), patched.body());
        assertTrue(patched.body().contains("\"title\":\"new\""), patched.body());
        assertTrue(patched.body().contains("\"priority\":null"), patched.body());

        HttpResponse<String> badPriority = patch("/api/tickets/META-4", "{\"priority\":\"P9\"}");
        assertTrue(badPriority.statusCode() >= 400, badPriority.body());
    }

    @Test
    void patch_round_trips_description_note_and_labels() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"META-EDIT\",\"title\":\"old\"}");
        HttpResponse<String> patched = patch("/api/tickets/META-EDIT",
                "{\"title\":\"new\",\"description\":\"验收标准\","
                        + "\"note\":\"需要和前端一起确认\",\"labels\":[\"前端\",\"体验,专项\"]}");
        assertEquals(200, patched.statusCode(), patched.body());
        assertTrue(patched.body().contains("\"description\":\"验收标准\""), patched.body());
        assertTrue(patched.body().contains("\"note\":\"需要和前端一起确认\""), patched.body());
        assertTrue(patched.body().contains("\"labels\":[\"前端\",\"体验,专项\"]"), patched.body());

        HttpResponse<String> cleared = patch("/api/tickets/META-EDIT",
                "{\"description\":null,\"note\":\"\",\"labels\":[]}");
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(cleared.body().contains("\"description\":null"), cleared.body());
        assertTrue(cleared.body().contains("\"note\":null"), cleared.body());
        assertTrue(cleared.body().contains("\"labels\":[]"), cleared.body());
    }

    @Test
    void stage_moves_are_limited_to_queue_transitions() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"META-5\",\"title\":\"t\"}");

        // V19: cancelling is operator-driven and must carry a reason — no silent cancels.
        HttpResponse<String> silentCancel = patch("/api/tickets/META-5", "{\"stage\":\"CANCELLED\"}");
        assertEquals(400, silentCancel.statusCode(), silentCancel.body());
        assertTrue(silentCancel.body().contains("reason"), silentCancel.body());

        HttpResponse<String> cancel = patch("/api/tickets/META-5",
                "{\"stage\":\"CANCELLED\",\"reason\":\"需求取消，不再投入\"}");
        assertEquals(200, cancel.statusCode(), cancel.body());
        assertTrue(cancel.body().contains("\"stage\":\"CANCELLED\""), cancel.body());

        // T-117: reviving a terminal ticket without a reason is refused — no silent revives.
        HttpResponse<String> silent = patch("/api/tickets/META-5", "{\"stage\":\"IN_PROGRESS\"}");
        assertEquals(400, silent.statusCode(), silent.body());
        assertTrue(silent.body().contains("requires a non-blank reason"), silent.body());

        HttpResponse<String> reopen = patch("/api/tickets/META-5",
                "{\"stage\":\"IN_PROGRESS\",\"restart_reason\":\"需求变更，重新开启\"}");
        assertEquals(200, reopen.statusCode(), reopen.body());
        assertTrue(reopen.body().contains("\"stage\":\"IN_PROGRESS\""), reopen.body());
        assertTrue(reopen.body().contains("\"restart_count\":1"), reopen.body());

        // The restart history endpoint records the row the reopen created.
        HttpResponse<String> restarts = get("/api/tickets/META-5/restarts");
        assertEquals(200, restarts.statusCode(), restarts.body());
        assertTrue(restarts.body().contains("\"reason\":\"需求变更，重新开启\""), restarts.body());
        assertTrue(restarts.body().contains("\"from_stage\":\"CANCELLED\""), restarts.body());

        // Review-gated stages must go through presubmit/review/publish — never a direct PATCH.
        HttpResponse<String> gated = patch("/api/tickets/META-5", "{\"stage\":\"IN_REVIEW\"}");
        assertTrue(gated.statusCode() >= 400, "IN_REVIEW is review-gated: " + gated.body());
        assertTrue(gated.body().contains("review-gated"), gated.body());

        // 进行中可退回待处理（看板拖回重新排队）——队列内流转，无需理由。
        HttpResponse<String> backToPending = patch("/api/tickets/META-5", "{\"stage\":\"PENDING\"}");
        assertEquals(200, backToPending.statusCode(), backToPending.body());
        assertTrue(backToPending.body().contains("\"stage\":\"PENDING\""), backToPending.body());
        // 退回后仍可重新开始（队列内来回均合法）。
        HttpResponse<String> restarted = patch("/api/tickets/META-5", "{\"stage\":\"IN_PROGRESS\"}");
        assertEquals(200, restarted.statusCode(), restarted.body());
    }

    @Test
    void terminal_tickets_reject_every_stage_change_except_reasoned_restart() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"META-TERM\",\"title\":\"t\"}");
        assertEquals(200, patch("/api/tickets/META-TERM",
                "{\"stage\":\"CANCELLED\",\"reason\":\"误建工单\"}").statusCode());

        // 终态不出门：已取消 → 强制完成（即截图里 CANCELLED → DONE 的场景）必须被拒，
        // 且报错指向「重启」而不是误导性的 review-gated 文案。
        HttpResponse<String> forceDone = patch("/api/tickets/META-TERM",
                "{\"stage\":\"DONE\",\"reason\":\"已在 zcode 完成\"}");
        assertEquals(400, forceDone.statusCode(), forceDone.body());
        assertTrue(forceDone.body().contains("terminal ticket cannot change stage"), forceDone.body());

        // 已完成 → 已取消 同样拒绝（终态之间互转一律不出门）。
        post("/api/tickets", "{\"ticket_no\":\"META-TERM2\",\"title\":\"t\"}");
        assertEquals(200, patch("/api/tickets/META-TERM2",
                "{\"stage\":\"DONE\",\"reason\":\"直接收尾\"}").statusCode());
        HttpResponse<String> cancelDone = patch("/api/tickets/META-TERM2",
                "{\"stage\":\"CANCELLED\",\"reason\":\"改成取消\"}");
        assertEquals(400, cancelDone.statusCode(), cancelDone.body());
        assertTrue(cancelDone.body().contains("terminal ticket cannot change stage"), cancelDone.body());
    }

    @Test
    void force_complete_carries_reason_and_records_history() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"META-7\",\"title\":\"t\"}");

        // V19: force-drag to DONE bypasses the gate but must carry a reason.
        HttpResponse<String> silent = patch("/api/tickets/META-7", "{\"stage\":\"DONE\"}");
        assertEquals(400, silent.statusCode(), silent.body());

        HttpResponse<String> done = patch("/api/tickets/META-7",
                "{\"stage\":\"DONE\",\"reason\":\"人工验证效果已达成，跳过门禁收尾\"}");
        assertEquals(200, done.statusCode(), done.body());
        assertTrue(done.body().contains("\"stage\":\"DONE\""), done.body());
        assertTrue(done.body().contains("\"stage_change_count\":1"), done.body());
        // The unified history records the force-complete row; the legacy badge counts revives only.
        assertTrue(done.body().contains("\"restart_count\":0"), done.body());

        HttpResponse<String> changes = get("/api/tickets/META-7/stage-changes");
        assertEquals(200, changes.statusCode(), changes.body());
        assertTrue(changes.body().contains("\"kind\":\"force_complete\""), changes.body());
        assertTrue(changes.body().contains("\"to_stage\":\"DONE\""), changes.body());
        assertTrue(changes.body().contains("\"from_stage\":\"IN_PROGRESS\""), changes.body());
        assertTrue(changes.body().contains("人工验证效果已达成"), changes.body());
    }

    @Test
    void working_diff_returns_tracked_changes_and_untracked_files() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"META-6\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("META-6");

        // A tracked file needs a commit in the clone first; pass an explicit identity so the
        // commit does not depend on the machine's global git config.
        Files.writeString(clone.resolve("tracked.txt"), "v1\n");
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost");
        var git = harness.components().git();
        git.must(clone, env, "add", "tracked.txt");
        git.must(clone, env, "commit", "-m", "seed");

        Files.writeString(clone.resolve("tracked.txt"), "v2 with change\n");
        Files.writeString(clone.resolve("fresh.txt"), "brand new\n");

        HttpResponse<String> res = get("/api/tickets/META-6/diff");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"source\":\"working\""), res.body());
        assertTrue(res.body().contains("v2 with change"), "tracked modification missing: " + res.body());
        assertTrue(res.body().contains("new file mode 100644"), res.body());
        assertTrue(res.body().contains("brand new"), "untracked file missing: " + res.body());
        assertTrue(res.body().contains("tracked.txt"), res.body());
    }

    @Test
    void patch_reassigns_and_detaches_agent_config() throws Exception {
        post("/api/agent-configs", """
                {"id":"agent-assign","name":"Assign Agent","cli":"CLAUDE",
                 "system_prompt":null,"extra_flags":[],"description":"drawer assignment"}
                """);
        post("/api/tickets", "{\"ticket_no\":\"META-AGENT\",\"title\":\"t\"}");

        HttpResponse<String> assigned = patch("/api/tickets/META-AGENT",
                "{\"agent_config_id\":\"agent-assign\"}");
        assertEquals(200, assigned.statusCode(), assigned.body());
        assertTrue(assigned.body().contains("\"agent_config_id\":\"agent-assign\""), assigned.body());

        // Blank string detaches — same semantics as null (back to manual handling).
        HttpResponse<String> detached = patch("/api/tickets/META-AGENT",
                "{\"agent_config_id\":\"\"}");
        assertEquals(200, detached.statusCode(), detached.body());
        assertTrue(detached.body().contains("\"agent_config_id\":null"), detached.body());

        HttpResponse<String> unknown = patch("/api/tickets/META-AGENT",
                "{\"agent_config_id\":\"ghost\"}");
        assertTrue(unknown.statusCode() >= 400, "unknown agent config must be rejected: " + unknown.body());
    }

    @Test
    void create_without_ticket_no_generates_sequential_numbers() throws Exception {
        // Fresh gate: the first server-side number is T-101, then T-102.
        HttpResponse<String> first = post("/api/tickets", "{\"title\":\"auto one\"}");
        assertEquals(201, first.statusCode(), first.body());
        assertEquals("T-101", extractStringField(first.body(), "ticket_no"), first.body());

        HttpResponse<String> second = post("/api/tickets", "{\"title\":\"auto two\"}");
        assertEquals(201, second.statusCode(), second.body());
        assertEquals("T-102", extractStringField(second.body(), "ticket_no"), second.body());

        // Blank ticket_no is treated as omitted — the server mints the next number.
        HttpResponse<String> blank = post("/api/tickets", "{\"ticket_no\":\"  \",\"title\":\"blank\"}");
        assertEquals(201, blank.statusCode(), blank.body());
        assertEquals("T-103", extractStringField(blank.body(), "ticket_no"), blank.body());

        // An explicit high number pushes generation past it; explicit ids still work.
        post("/api/tickets", "{\"ticket_no\":\"T-110\",\"title\":\"explicit high\"}");
        HttpResponse<String> after = post("/api/tickets", "{\"title\":\"after high\"}");
        assertEquals(201, after.statusCode(), after.body());
        assertEquals("T-111", extractStringField(after.body(), "ticket_no"), after.body());

        // Same behaviour on the project-scoped board entry point. The project's quick-mode super
        // ticket (V19) is provisioned on create and shares the numbering pool, taking T-112 first.
        String projectId = createProject("Scoped", "scoped-ws");
        HttpResponse<String> scoped = post("/api/projects/" + projectId + "/tickets",
                "{\"title\":\"scoped auto\"}");
        assertEquals(201, scoped.statusCode(), scoped.body());
        assertEquals("T-113", extractStringField(scoped.body(), "ticket_no"), scoped.body());
        assertTrue(scoped.body().contains("\"project_id\":\"" + projectId + "\""), scoped.body());
    }

    @Test
    void create_with_explicit_ticket_no_and_duplicate_rejection() throws Exception {
        HttpResponse<String> created = post("/api/tickets",
                "{\"ticket_no\":\"EXPL-1\",\"title\":\"explicit\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"ticket_no\":\"EXPL-1\""), created.body());

        HttpResponse<String> duplicate = post("/api/tickets",
                "{\"ticket_no\":\"EXPL-1\",\"title\":\"again\"}");
        assertTrue(duplicate.statusCode() >= 400, "duplicate ticket_no must be rejected: "
                + duplicate.body());
        assertTrue(duplicate.body().contains("already exists"), duplicate.body());
    }

    @Test
    void create_stage_pending_allowed_and_other_stages_rejected() throws Exception {
        HttpResponse<String> pending = post("/api/tickets",
                "{\"ticket_no\":\"STG-PEND\",\"title\":\"queued\",\"stage\":\"PENDING\"}");
        assertEquals(201, pending.statusCode(), pending.body());
        assertTrue(pending.body().contains("\"stage\":\"PENDING\""), pending.body());
        // PENDING creation still performs the clone takeover (TopologyInitializer runs as usual).
        assertTrue(Files.isDirectory(harness.components().config().clonesRoot()
                .resolve("STG-PEND").resolve(".git")), "clone must exist for a PENDING ticket");

        // Default remains IN_PROGRESS (the current behaviour).
        post("/api/tickets", "{\"ticket_no\":\"STG-DEF\",\"title\":\"default\"}");
        HttpResponse<String> def = get("/api/tickets/STG-DEF");
        assertTrue(def.body().contains("\"stage\":\"IN_PROGRESS\""), def.body());

        // PENDING → IN_PROGRESS is a legal queue transition afterwards.
        HttpResponse<String> started = patch("/api/tickets/STG-PEND", "{\"stage\":\"IN_PROGRESS\"}");
        assertEquals(200, started.statusCode(), started.body());
        assertTrue(started.body().contains("\"stage\":\"IN_PROGRESS\""), started.body());

        // Only the two queue-entry stages may be requested at create time.
        HttpResponse<String> done = post("/api/tickets",
                "{\"ticket_no\":\"STG-DONE\",\"title\":\"nope\",\"stage\":\"DONE\"}");
        assertTrue(done.statusCode() >= 400, "stage DONE on create must be rejected: " + done.body());
        HttpResponse<String> review = post("/api/tickets",
                "{\"ticket_no\":\"STG-REV\",\"title\":\"nope\",\"stage\":\"IN_REVIEW\"}");
        assertTrue(review.statusCode() >= 400, "stage IN_REVIEW on create must be rejected: "
                + review.body());
    }

    @Test
    void project_create_and_update_responses_carry_real_ticket_counts() throws Exception {
        String projectId = createProject("Counted", "counted-ws");
        // A brand-new project starts at zero — computed, not hardcoded (projectCreate path).
        HttpResponse<String> created = get("/api/projects");
        assertTrue(created.body().contains("\"ticket_count\":0"), created.body());
        assertTrue(created.body().contains("\"active_ticket_count\":0"), created.body());

        // Two tickets on the board: one active, one terminal via a reason-carrying cancel (V19).
        post("/api/tickets", "{\"ticket_no\":\"CNT-1\",\"title\":\"a\",\"project_id\":\"" + projectId + "\"}");
        post("/api/tickets", "{\"ticket_no\":\"CNT-2\",\"title\":\"b\",\"project_id\":\"" + projectId + "\"}");
        assertEquals(200, patch("/api/tickets/CNT-2",
                "{\"stage\":\"CANCELLED\",\"reason\":\"不再需要\"}").statusCode());

        // projectUpdate shares projectJson: counts must now be 2 total / 1 active.
        HttpResponse<String> updated = put("/api/projects/" + projectId, "{\"name\":\"Renamed\"}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"ticket_count\":2"), updated.body());
        assertTrue(updated.body().contains("\"active_ticket_count\":1"), updated.body());
    }

    private String createProject(String name, String dirName) throws Exception {
        Path ws = harness.root().resolve(dirName);
        HttpResponse<String> res = post("/api/projects",
                "{\"name\":\"" + name + "\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, res.statusCode(), res.body());
        return extractStringField(res.body(), "id");
    }

    /** JSON-escapes Windows path separators. */
    private static String json(String value) {
        return value.replace("\\", "\\\\");
    }

    private static String extractStringField(String json, String field) {
        int i = json.indexOf("\"" + field + "\":\"");
        if (i < 0) {
            throw new AssertionError("field " + field + " missing in " + json);
        }
        int start = i + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
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

    private HttpResponse<String> patch(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
