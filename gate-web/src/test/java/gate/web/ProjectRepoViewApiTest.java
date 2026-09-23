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
import org.junit.jupiter.api.Tag;

/**
 * 项目 → 仓库视图 (web console): GET /api/projects/{id}/repo (branch/commit graph with lanes) and
 * GET /api/projects/{id}/tree[/{path}] (lazy directory listing with last-commit attribution),
 * driven against a real workspace repo built with the real git binary.
 */
@Tag("slow")
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

    @Test
    void commit_detail_returns_full_message_meta_and_refs() throws Exception {
        Path ws = buildWorkspace("detail-ws");
        String id = registerProject("Detail", ws);

        // 列表只带单行 subject；详情才给完整 message。
        String sha = commitMultiLine(ws, "feat(graph): multi-line subject",
                "why: the subject cannot say all this\n\n- bullet one\n- bullet two\n");
        String full = get("/api/projects/" + id + "/commit/" + sha).body();
        assertEquals(200, get("/api/projects/" + id + "/commit/" + sha).statusCode(), full);
        assertTrue(full.contains("\"message\":\"feat(graph): multi-line subject\\n\\nwhy: the subject"
                + " cannot say all this\\n\\n- bullet one\\n- bullet two\""), full);
        assertTrue(full.contains("\"sha\":\"" + sha + "\""), full);
        assertTrue(full.contains("\"author\":\"li\""), full);
        assertTrue(full.contains("\"author_email\":\"li@localhost\""), full);
        assertTrue(full.contains("\"author_date\":\""), full);
        assertTrue(full.contains("\"committer_date\":\""), full);
        // --contains 是「祖先可达」而非「tip 等于」：新提交只在 main 上，tag 指向更早的 root。
        assertTrue(full.contains("\"refs\":[\"refs/heads/main\"]"), full);

        // 根提交：无父，且被两个分支与 v1.0.0（正指向它）同时包含（--contains 看祖先可达）。
        String rootSha = harness.components().git()
                .line(gate.domain.git.RepoRef.of(ws), "rev-list", "--max-parents=0", "HEAD").trim();
        String rootDetail = get("/api/projects/" + id + "/commit/" + rootSha).body();
        assertEquals(200, get("/api/projects/" + id + "/commit/" + rootSha).statusCode(), rootDetail);
        assertTrue(rootDetail.contains("\"parents\":[]"), rootDetail);
        assertTrue(rootDetail.contains(
                "\"refs\":[\"refs/heads/feature/one\",\"refs/heads/main\",\"tag: v1.0.0\"]"), rootDetail);
    }

    @Test
    void commit_detail_rejects_non_hex_and_unknown_sha() throws Exception {
        Path ws = buildWorkspace("bad-sha-ws");
        String id = registerProject("BadSha", ws);

        // 非十六进制对象名（含会被 git 当成选项的入参）一律拒绝：这个值直接进 git argv。
        for (String bad : new String[] {"not-hex", "HEAD", "--upload-pack=x", "%2D%2Dupload-pack%3Dx"}) {
            HttpResponse<String> res = get("/api/projects/" + id + "/commit/" + bad);
            assertEquals(400, res.statusCode(), bad + " -> " + res.body());
        }
        // 格式合法但不存在的对象：git 读不到，400 而不是 500。
        HttpResponse<String> unknown = get("/api/projects/" + id + "/commit/"
                + "ffffffffffffffffffffffffffffffffffffffff");
        assertEquals(400, unknown.statusCode(), unknown.body());
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

    /** 落一个带 body 的提交，返回完整 sha（当前工作区 git 身份是 li，见 buildWorkspace）。 */
    private String commitMultiLine(Path ws, String subject, String body) throws Exception {
        var git = harness.components().git();
        Files.writeString(ws.resolve("notes.md"), "# notes\n");
        git.must(ws, Map.of(), "add", "-A");
        git.must(ws, Map.of(), "commit", "-m", subject, "-m", body);
        return git.line(gate.domain.git.RepoRef.of(ws), "rev-parse", "HEAD").trim();
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
