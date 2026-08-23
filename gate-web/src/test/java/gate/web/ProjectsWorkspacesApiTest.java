package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * V5 project registry + workspace picker (web console): codex-style "select a workspace → create a
 * project", duplicate-workspace guard, per-project ticket counts, and delete-detach semantics.
 */
class ProjectsWorkspacesApiTest {

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
    void workspace_browse_lists_directories_with_flags() throws Exception {
        Path root = harness.root();
        Files.createDirectories(root.resolve("plain-dir"));
        Path repoDir = root.resolve("repo-dir");
        Files.createDirectories(repoDir);
        harness.components().git().must(repoDir, java.util.Map.of(), "init");

        HttpResponse<String> res = post("/api/workspaces",
                "{\"path\":\"" + root.toString().replace("\\", "\\\\") + "\"}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"plain-dir\""), res.body());
        assertTrue(res.body().contains("\"repo-dir\""), res.body());
        assertTrue(res.body().contains("\"is_git_repo\":false"), res.body());
        assertTrue(res.body().contains("\"is_git_repo\":true"), res.body());
        assertTrue(res.body().contains("\"exists\":true"), res.body());

        HttpResponse<String> missing = post("/api/workspaces", "{\"path\":\"" + json(root.resolve("nope").toString()) + "\"}");
        assertEquals(200, missing.statusCode(), "missing dir is a normal empty state: " + missing.body());
        assertTrue(missing.body().contains("\"exists\":false"), missing.body());

        HttpResponse<String> home = get("/api/workspaces");
        assertEquals(200, home.statusCode(), home.body());
        assertTrue(home.body().contains("\"directories\""), home.body());
        assertTrue(home.body().contains("\"roots\":"), "filesystem roots must be listed: " + home.body());
    }

    @Test
    void project_create_inits_git_and_rejects_duplicate_workspace() throws Exception {
        Path ws = harness.root().resolve("proj-ws");
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"My App!\",\"workspace_path\":\"" + json(ws.toString()) + "\",\"init_git\":true}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"id\":\"my-app\""), created.body());
        assertTrue(Files.isDirectory(ws.resolve(".git")), "init_git must run git init: " + ws);

        HttpResponse<String> dup = post("/api/projects",
                "{\"name\":\"Other\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertTrue(dup.statusCode() >= 400, "duplicate workspace must be rejected: " + dup.body());
    }

    @Test
    void project_listing_counts_tickets_and_delete_detaches() throws Exception {
        Path ws = harness.root().resolve("count-ws");
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"Counted\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = extract(created.body(), "id");

        post("/api/tickets", "{\"ticket_no\":\"PROJ-1\",\"title\":\"a\",\"project_id\":\"" + id + "\"}");
        post("/api/tickets", "{\"ticket_no\":\"PROJ-2\",\"title\":\"b\",\"project_id\":\"" + id + "\"}");

        HttpResponse<String> list = get("/api/projects");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"ticket_count\":2"), list.body());
        assertTrue(list.body().contains("\"active_ticket_count\":2"), list.body());

        HttpResponse<String> rename = put("/api/projects/" + id, "{\"name\":\"Renamed\"}");
        assertEquals(200, rename.statusCode(), rename.body());
        assertTrue(rename.body().contains("\"name\":\"Renamed\""), rename.body());

        HttpResponse<String> detail = get("/api/tickets/PROJ-1");
        assertTrue(detail.body().contains("\"project\":\"Renamed\""), detail.body());

        HttpResponse<String> deleted = delete("/api/projects/" + id);
        assertEquals(200, deleted.statusCode(), deleted.body());
        HttpResponse<String> after = get("/api/tickets/PROJ-1");
        assertTrue(after.body().contains("\"project\":null"), "tickets must detach on delete: " + after.body());
        assertFalse(after.body().contains("\"project_id\":\"" + id + "\""), after.body());
    }

    @Test
    void project_ticket_board_lists_and_creates_only_within_its_project() throws Exception {
        String alpha = extract(post("/api/projects",
                "{\"name\":\"Alpha\",\"workspace_path\":\"" + json(harness.root().resolve("alpha-board").toString()) + "\"}").body(), "id");
        String beta = extract(post("/api/projects",
                "{\"name\":\"Beta\",\"workspace_path\":\"" + json(harness.root().resolve("beta-board").toString()) + "\"}").body(), "id");

        HttpResponse<String> alphaCreated = post("/api/projects/" + alpha + "/tickets",
                "{\"ticket_no\":\"ALPHA-1\",\"title\":\"alpha ticket\"}");
        assertEquals(201, alphaCreated.statusCode(), alphaCreated.body());
        assertTrue(alphaCreated.body().contains("\"project_id\":\"" + alpha + "\""), alphaCreated.body());

        HttpResponse<String> betaCreated = post("/api/projects/" + beta + "/tickets",
                "{\"ticket_no\":\"BETA-1\",\"title\":\"beta ticket\"}");
        assertEquals(201, betaCreated.statusCode(), betaCreated.body());

        HttpResponse<String> alphaList = get("/api/projects/" + alpha + "/tickets");
        assertEquals(200, alphaList.statusCode(), alphaList.body());
        assertTrue(alphaList.body().contains("ALPHA-1"), alphaList.body());
        assertFalse(alphaList.body().contains("BETA-1"), alphaList.body());

        HttpResponse<String> crossProjectDetail = get("/api/projects/" + alpha + "/tickets/BETA-1");
        assertTrue(crossProjectDetail.statusCode() >= 400, crossProjectDetail.body());

        HttpResponse<String> crossProjectUpdate = patch("/api/projects/" + alpha + "/tickets/BETA-1",
                "{\"stage\":\"CANCELLED\"}");
        assertTrue(crossProjectUpdate.statusCode() >= 400, crossProjectUpdate.body());

        HttpResponse<String> conflictingCreate = post("/api/projects/" + alpha + "/tickets",
                "{\"ticket_no\":\"ALPHA-2\",\"title\":\"wrong project\",\"project_id\":\"" + beta + "\"}");
        assertTrue(conflictingCreate.statusCode() >= 400, conflictingCreate.body());
    }

    @Test
    void project_tickets_clone_from_the_project_auth_repo_not_the_gate_repo() throws Exception {
        // T-107 regression: one project's history in the shared gate-level auth repo must never
        // become another project's clone base — project tickets clone from the project's own repo.
        Path ws = harness.root().resolve("iso-ws");
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"Iso\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = extract(created.body(), "id");
        String projectAuth = extract(created.body(), "auth_repo");
        assertTrue(projectAuth.contains("auth-iso.git"),
                "project registration must provision a dedicated auth repo: " + projectAuth);

        HttpResponse<String> ticket = post("/api/projects/" + id + "/tickets",
                "{\"ticket_no\":\"ISO-1\",\"title\":\"iso ticket\"}");
        assertEquals(201, ticket.statusCode(), ticket.body());
        Path clone = Path.of(extract(ticket.body(), "clone_path"));
        var origin = harness.components().git().run(clone.getParent(), java.util.Map.of(),
                "-C", clone.toString(), "remote", "get-url", "origin");
        assertTrue(origin.ok(), origin.stdout() + origin.stderr());
        assertEquals(Path.of(projectAuth).toAbsolutePath().normalize().toString(),
                Path.of(origin.stdout().trim()).toAbsolutePath().normalize().toString(),
                "project tickets must clone from the project's auth repo");

        // Unaffiliated tickets keep cloning from the gate-level topology.
        HttpResponse<String> plain = post("/api/tickets", "{\"ticket_no\":\"PLAIN-1\",\"title\":\"plain\"}");
        assertEquals(201, plain.statusCode(), plain.body());
        Path plainClone = Path.of(extract(plain.body(), "clone_path"));
        var plainOrigin = harness.components().git().run(plainClone.getParent(), java.util.Map.of(),
                "-C", plainClone.toString(), "remote", "get-url", "origin");
        assertTrue(plainOrigin.ok(), plainOrigin.stdout() + plainOrigin.stderr());
        assertEquals(harness.components().config().authRepo().toAbsolutePath().normalize().toString(),
                Path.of(plainOrigin.stdout().trim()).toAbsolutePath().normalize().toString(),
                "unaffiliated tickets keep the gate-level auth repo");
    }

    @Test
    void project_meta_priority_size_tags_roundtrip() throws Exception {
        Path ws = harness.root().resolve("meta-ws");
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"Meta\",\"workspace_path\":\"" + json(ws.toString())
                        + "\",\"priority\":\"P1\",\"size\":\"medium\",\"tags\":[\"backend\",\"支付,核心\"]}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"priority\":\"P1\""), created.body());
        assertTrue(created.body().contains("\"size\":\"medium\""), created.body());
        assertTrue(created.body().contains("\"tags\":[\"backend\",\"支付,核心\"]"),
                "tags round-trip incl. comma + non-ASCII: " + created.body());
        String id = extract(created.body(), "id");

        HttpResponse<String> updated = put("/api/projects/" + id, "{\"priority\":\"P0\",\"tags\":[]}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"priority\":\"P0\""), updated.body());
        assertTrue(updated.body().contains("\"tags\":[]"), updated.body());
        assertTrue(updated.body().contains("\"size\":\"medium\""),
                "absent size key must keep the stored value: " + updated.body());

        HttpResponse<String> cleared = put("/api/projects/" + id, "{\"priority\":null}");
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(cleared.body().contains("\"priority\":null"), cleared.body());

        HttpResponse<String> badPriority = put("/api/projects/" + id, "{\"priority\":\"P9\"}");
        assertTrue(badPriority.statusCode() >= 400, "invalid priority must be rejected: " + badPriority.body());

        HttpResponse<String> badSize = put("/api/projects/" + id, "{\"size\":\"huge\"}");
        assertTrue(badSize.statusCode() >= 400, "invalid size must be rejected: " + badSize.body());

        HttpResponse<String> list = get("/api/projects");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"priority\":null"), list.body());
    }

    @Test
    void project_target_ref_persists_effective_default_and_updates() throws Exception {
        String primary = harness.components().config().primaryTargetRef();
        Path ws = harness.root().resolve("target-ref-ws");
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"TargetRef\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"target_ref\":\"" + primary + "\""),
                "absent target_ref must persist the gate primary, not null: " + created.body());
        String id = extract(created.body(), "id");

        HttpResponse<String> rename = put("/api/projects/" + id, "{\"name\":\"Renamed\"}");
        assertEquals(200, rename.statusCode(), rename.body());
        assertTrue(rename.body().contains("\"target_ref\":\"" + primary + "\""),
                "absent target_ref key must keep the stored value: " + rename.body());

        HttpResponse<String> updated = put("/api/projects/" + id,
                "{\"target_ref\":\"refs/heads/release\"}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"target_ref\":\"refs/heads/release\""), updated.body());

        HttpResponse<String> reset = put("/api/projects/" + id, "{\"target_ref\":null}");
        assertEquals(200, reset.statusCode(), reset.body());
        assertTrue(reset.body().contains("\"target_ref\":\"" + primary + "\""),
                "present-but-null target_ref resets to the gate primary: " + reset.body());
    }

    /** JSON-escapes Windows path separators. */
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

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .DELETE().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
