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

/**
 * Ticket metadata (web console): priority + project tagging on create, editable content, the PATCH
 * edit endpoint, queue-stage guard rails, and the live working-tree diff that replaces the UI's
 * sample diff.
 */
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

        HttpResponse<String> cancel = patch("/api/tickets/META-5", "{\"stage\":\"CANCELLED\"}");
        assertEquals(200, cancel.statusCode(), cancel.body());
        assertTrue(cancel.body().contains("\"stage\":\"CANCELLED\""), cancel.body());

        HttpResponse<String> reopen = patch("/api/tickets/META-5", "{\"stage\":\"IN_PROGRESS\"}");
        assertEquals(200, reopen.statusCode(), reopen.body());

        // Review-gated stages must go through presubmit/review/publish — never a direct PATCH.
        HttpResponse<String> gated = patch("/api/tickets/META-5", "{\"stage\":\"IN_REVIEW\"}");
        assertTrue(gated.statusCode() >= 400, "IN_REVIEW is review-gated: " + gated.body());
        assertTrue(gated.body().contains("review-gated"), gated.body());
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
}
