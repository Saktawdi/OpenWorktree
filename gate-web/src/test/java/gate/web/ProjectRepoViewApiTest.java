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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 项目 → 仓库视图 (web console): GET /api/projects/{id}/repo (branch/commit graph with lanes) and
 * GET /api/projects/{id}/tree[/{path}] (lazy directory listing with last-commit attribution),
 * driven against a real workspace repo built with the real git binary.
 */
class ProjectRepoViewApiTest {

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
    void repo_view_reports_branches_lanes_and_commit_refs() throws Exception {
        Path ws = buildWorkspace("graph-ws");
        String id = registerProject("Graph", ws);

        HttpResponse<String> res = get("/api/projects/" + id + "/repo");
        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();

        assertTrue(body.contains("\"repo_path\":\"" + json(ws.toString()) + "\""), body);
        assertTrue(body.contains("\"head\":\"refs/heads/main\""), body);
        assertTrue(body.contains("\"auth\":"), body);
        assertTrue(body.contains("\"name\":\"refs/heads/main\""), body);
        assertTrue(body.contains("\"name\":\"refs/heads/feature/one\""), body);
        // Root commit carries no parents; the tag is attached to its commit with the UI's "tag:" form.
        assertTrue(body.contains("\"parents\":[]"), body);
        assertTrue(body.contains("tag: v1.0.0"), body);
        // Commit rows carry every field the graph console renders.
        assertTrue(body.contains("\"message\":\"init: root\""), body);
        assertTrue(body.contains("\"author\":\"zhang\""), body);
        assertTrue(body.contains("\"lane\":0"), body);
        // Two branches fan out into at least two distinct lanes.
        assertTrue(body.contains("\"lane\":1"), body);
    }

    @Test
    void repo_view_rejects_missing_project_and_non_git_workspace() throws Exception {
        HttpResponse<String> missing = get("/api/projects/nope/repo");
        assertTrue(missing.statusCode() >= 400, missing.body());

        Path plain = Files.createDirectories(harness.root().resolve("plain-ws"));
        String id = registerProject("Plain", plain);
        HttpResponse<String> notGit = get("/api/projects/" + id + "/repo");
        assertEquals(400, notGit.statusCode(), notGit.body());
        assertTrue(notGit.body().contains("not a git repository"), notGit.body());
    }

    @Test
    void repo_view_of_empty_repo_is_an_empty_state_not_an_error() throws Exception {
        Path ws = harness.root().resolve("empty-ws");
        Files.createDirectories(ws);
        harness.components().git().must(ws, Map.of(), "init", "-b", "main");
        String id = registerProject("Empty", ws);

        HttpResponse<String> res = get("/api/projects/" + id + "/repo");
        assertEquals(200, res.statusCode(), res.body());
        // Unborn HEAD still reports the branch name — the console shows an empty graph, not an error.
        assertTrue(res.body().contains("\"head\":\"refs/heads/main\""), res.body());
        assertTrue(res.body().contains("\"commits\":[]"), res.body());
        assertTrue(res.body().contains("\"branches\":[]"), res.body());

        HttpResponse<String> tree = get("/api/projects/" + id + "/tree");
        assertEquals(200, tree.statusCode(), tree.body());
        assertTrue(tree.body().contains("\"entries\":[]"), tree.body());
    }

    @Test
    void tree_lists_root_and_subdirectory_with_last_commit_attribution() throws Exception {
        Path ws = buildWorkspace("tree-ws");
        String id = registerProject("Tree", ws);

        HttpResponse<String> root = get("/api/projects/" + id + "/tree");
        assertEquals(200, root.statusCode(), root.body());
        String body = root.body();
        // Dirs sort before files; every entry carries its last commit's short sha + subject.
        assertTrue(body.contains("\"path\":\"src\",\"type\":\"dir\""), body);
        assertTrue(body.contains("\"path\":\"README.md\",\"type\":\"file\""), body);
        assertTrue(body.contains("\"last_commit_short\":\""), body);
        assertTrue(body.contains("\"last_message\":\"init: root\""), body);
        assertTrue(body.contains("\"last_message\":\"docs: explain settings\""), body);

        HttpResponse<String> sub = get("/api/projects/" + id + "/tree/src/app");
        assertEquals(200, sub.statusCode(), sub.body());
        String subBody = sub.body();
        assertTrue(subBody.contains("\"path\":\"src/app/main.ts\",\"type\":\"file\""), subBody);
        // The latest commit under the dir wins the dir's own attribution.
        assertTrue(subBody.contains("\"last_message\":\"docs: explain settings\""), subBody);

        HttpResponse<String> missing = get("/api/projects/" + id + "/tree/no/such/dir");
        assertEquals(200, missing.statusCode(), "unknown dir is an empty listing: " + missing.body());
        assertTrue(missing.body().contains("\"entries\":[]"), missing.body());

        // Encoded dot-dot still decodes to ".." in the router — must be rejected, not treated as root.
        HttpResponse<String> escape = get("/api/projects/" + id + "/tree/%2e%2e");
        assertTrue(escape.statusCode() >= 400, "path traversal must be rejected: " + escape.body());
    }

    /** main: init (tag v1.0.0) → merge of feature/one (two commits by another author). */
    private Path buildWorkspace(String name) throws Exception {
        Path ws = harness.root().resolve(name);
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.must(ws, Map.of(), "init", "-b", "main");
        git.must(ws, Map.of(), "config", "user.name", "zhang");
        git.must(ws, Map.of(), "config", "user.email", "zhang@localhost");
        commit(git, ws, "init: root", Map.of(
                "README.md", "hello\n",
                "src/app/main.ts", "export {}\n",
                "src/lib/util.ts", "export const x = 1;\n"));
        git.must(ws, Map.of(), "tag", "v1.0.0");
        git.must(ws, Map.of(), "checkout", "-b", "feature/one");
        commit(git, ws, "feat(one): settings doc", Map.of(
                "src/app/settings.md", "# settings\n"));
        git.must(ws, Map.of(), "config", "user.name", "li");
        git.must(ws, Map.of(), "config", "user.email", "li@localhost");
        commit(git, ws, "docs: explain settings", Map.of(
                "src/app/settings.md", "# settings\nAll knobs live here.\n"));
        git.must(ws, Map.of(), "checkout", "main");
        git.must(ws, Map.of(), "merge", "--no-ff", "-m", "Merge branch 'feature/one'", "feature/one");
        return ws;
    }

    private void commit(gate.adapters.git.GitCli git, Path ws, String message, Map<String, String> files)
            throws Exception {
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path file = ws.resolve(f.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, f.getValue());
        }
        git.must(ws, Map.of(), "add", "-A");
        git.must(ws, Map.of(), "commit", "-m", message);
    }

    private String registerProject(String name, Path ws) throws Exception {
        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"" + name + "\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        return extract(created.body(), "id");
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
}
