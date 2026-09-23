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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 工单 → 仓库视图 (web console): GET /api/tickets/{no}/repo (分支图/提交历史) and
 * GET /api/tickets/{no}/commit/<sha>, driven against the real ticket clone built by the real gate
 * topology.
 *
 * <p>重点覆盖两类仓库路径：普通工单读自己的隔离克隆；快速模式超级工单的 {@code clone_path} 就是
 * 项目工作区本身（不克隆），读到的必须是工作区历史而不是空仓库。
 */
@Tag("slow")
class TicketRepoApiTest {

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
    void ticket_repo_view_reports_the_clone_history() throws Exception {
        String[] proj = newProjectWithWorkspace("TicketRepoProj", "ticket-repo-ws");
        String projectId = proj[0];
        String ticketNo = "TR-1";
        String ticket = createTicket(ticketNo, projectId);

        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        assertTrue(Files.isDirectory(clone.resolve(".git")), ticket);

        HttpResponse<String> res = get("/api/tickets/" + ticketNo + "/repo");
        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();

        // 工单侧身份字段：读的是工单克隆，不是项目工作区。
        assertTrue(body.contains("\"ticket_no\":\"" + ticketNo + "\""), body);
        assertTrue(body.contains("\"repo_path\":\"" + json(clone.toString()) + "\""), body);
        assertTrue(body.contains("\"head\":\"refs/heads/" + ticketNo + "\""), body);
        // 没有 auth/sync 概念：工作台只画提交历史，落后判断与同步是项目页能力。
        assertTrue(!body.contains("\"auth\":"), body);
        // 克隆从基线切出，基线的提交都在图里。
        assertTrue(body.contains("\"message\":\"init: root\""), body);
        assertTrue(body.contains("\"refs\":[\"refs/heads/" + ticketNo + "\"]"), body);
    }

    @Test
    void super_ticket_repo_view_reads_the_project_workspace() throws Exception {
        String[] proj = newProjectWithWorkspace("SuperRepoProj", "super-repo-ws");
        Path ws = Path.of(proj[2]);
        String superNo = proj[3];
        assertTrue(superNo != null && !superNo.isBlank(), "project must expose its super ticket no");

        // 快速模式超级工单：clone_path IS the workspace，历史就是工作区历史。
        HttpResponse<String> res = get("/api/tickets/" + superNo + "/repo");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"repo_path\":\"" + json(ws.toString()) + "\""), res.body());
        assertTrue(res.body().contains("\"message\":\"init: root\""), res.body());

        // 工作区新提交即刻可见（视图是现读的，不依赖缓存）。
        String sha = commitIn(ws, "chore: after super ticket", Map.of("after.txt", "later\n"));
        HttpResponse<String> again = get("/api/tickets/" + superNo + "/repo");
        assertTrue(again.body().contains(sha), again.body());
    }

    @Test
    void commit_detail_returns_full_message_meta_and_refs() throws Exception {
        String[] proj = newProjectWithWorkspace("DetailProj", "detail-ws");
        String ticketNo = "TR-2";
        createTicket(ticketNo, proj[0]);
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);

        String sha = commitIn(clone, "feat(clone): multi-line subject", Map.of("a.txt", "a\n"),
                "why: the one-line subject cannot say all this\n\n- bullet one\n- bullet two\n");

        HttpResponse<String> res = get("/api/tickets/" + ticketNo + "/commit/" + sha);
        assertEquals(200, res.statusCode(), res.body());
        String body = res.body();

        // 完整 message：subject + body 的多行原文（JSON 里是 \n 转义）。
        assertTrue(body.contains("\"message\":\"feat(clone): multi-line subject\\n\\nwhy: the one-line"
                + " subject cannot say all this\\n\\n- bullet one\\n- bullet two\""), body);
        assertTrue(body.contains("\"sha\":\"" + sha + "\""), body);
        assertTrue(body.contains("\"author\":\"agent\""), body);
        assertTrue(body.contains("\"author_email\":\"agent@localhost\""), body);
        assertTrue(body.contains("\"author_date\":\""), body);
        assertTrue(body.contains("\"committer_date\":\""), body);
        // 克隆 HEAD 所在的工单分支（分支给全名，与图上一致）；tag 不在克隆里故不出现。
        assertTrue(body.contains("\"refs\":[\"refs/heads/" + ticketNo + "\"]"), body);
    }

    @Test
    void commit_detail_rejects_non_hex_and_unknown_sha() throws Exception {
        String[] proj = newProjectWithWorkspace("BadShaProj", "bad-sha-ws");
        String ticketNo = "TR-3";
        createTicket(ticketNo, proj[0]);
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        String sha = commitIn(clone, "init: root", Map.of("r.txt", "r\n"));

        // 非十六进制对象名（含会被 git 当成选项的入参）一律拒绝：这个值直接进 git argv。
        for (String bad : new String[] {"not-hex", "HEAD", "--upload-pack=x", "%2D%2Dupload-pack%3Dx"}) {
            HttpResponse<String> res = get("/api/tickets/" + ticketNo + "/commit/" + bad);
            assertEquals(400, res.statusCode(), bad + " -> " + res.body());
        }
        // 格式合法但不存在的对象：git 读不到，同样 400 而不是 500。
        HttpResponse<String> unknown = get("/api/tickets/" + ticketNo + "/commit/"
                + "ffffffffffffffffffffffffffffffffffffffff");
        assertEquals(400, unknown.statusCode(), unknown.body());

        // 真对象照常可读。
        assertEquals(200, get("/api/tickets/" + ticketNo + "/commit/" + sha).statusCode());
    }

    @Test
    void ticket_repo_view_rejects_missing_ticket_and_non_git_clone() throws Exception {
        String[] proj = newProjectWithWorkspace("BadCloneProj", "bad-clone-ws");
        String ticketNo = "TR-4";
        createTicket(ticketNo, proj[0]);

        HttpResponse<String> missing = get("/api/tickets/nope/repo");
        assertTrue(missing.statusCode() >= 400, missing.body());

        // 克隆目录还在但不再是 git 仓库 → 明确 400，不是 500 也不是空图。
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        deleteTree(clone.resolve(".git"));
        HttpResponse<String> notGit = get("/api/tickets/" + ticketNo + "/repo");
        assertEquals(400, notGit.statusCode(), notGit.body());
        assertTrue(notGit.body().contains("not a git repository"), notGit.body());
    }

    /* ─── helpers ─── */

    /** main: init（工作区自带提交），项目注册后与权威库对齐。 */
    private String[] newProjectWithWorkspace(String name, String wsDir) throws Exception {
        Path ws = harness.root().resolve(wsDir);
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.must(ws, Map.of(), "init", "-b", "main");
        git.must(ws, Map.of(), "config", "user.name", "zhang");
        git.must(ws, Map.of(), "config", "user.email", "zhang@localhost");
        commitIn(ws, "init: root", Map.of("README.md", "hello\n"));

        HttpResponse<String> created = post("/api/projects",
                "{\"name\":\"" + name + "\",\"workspace_path\":\"" + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = extract(created.body(), "id");
        String authRepo = extract(created.body(), "auth_repo");
        String superNo = extract(created.body(), "super_ticket_no");
        git.must(ws, Map.of(), "fetch", authRepo, "main");
        git.must(ws, Map.of(), "reset", "--hard", "FETCH_HEAD");
        return new String[] {projectId, authRepo, ws.toString(), superNo};
    }

    private String createTicket(String ticketNo, String projectId) throws Exception {
        HttpResponse<String> created = post("/api/tickets",
                "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"repo view\",\"project_id\":\"" + projectId + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        return created.body();
    }

    /** 在仓库里落一个提交，返回完整 sha。 */
    private String commitIn(Path repo, String subject, Map<String, String> files, String... body)
            throws Exception {
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path file = repo.resolve(f.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, f.getValue());
        }
        var git = harness.components().git();
        git.must(repo, Map.of(), "add", "-A");
        if (body.length > 0) {
            git.must(repo, Map.of(), "commit", "-m", subject, "-m", body[0]);
        } else {
            git.must(repo, Map.of(), "commit", "-m", subject);
        }
        return git.line(gate.domain.git.RepoRef.of(repo), "rev-parse", "HEAD").trim();
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
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
