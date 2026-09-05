package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
 * T-118 基座同步 API：POST /api/tickets/{no}/sync-base 把落后主分支的工单 clone 与权威分支快进到
 * base tip，未提交改动按 allow_dirty 语义跳过或 stash 重放；审查门禁期与终态工单拒绝同步。
 */
@Tag("slow")
class BaseSyncApiTest {

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
    void syncs_behind_clone_and_moves_ticket_branch() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"SYNC-1\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("SYNC-1");
        advanceMain(2);

        HttpResponse<String> res = post("/api/tickets/SYNC-1/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"status\":\"synced\""), res.body());
        assertTrue(res.body().contains("\"behind\":2"), res.body());
        assertTrue(res.body().contains("\"branch_moved\":true"), res.body());

        String mainTip = tip(harness.components().config().authRepo(), "refs/heads/main");
        assertEquals(mainTip, tip(clone, "HEAD"), "clone HEAD must sit on the base tip");
        assertEquals(mainTip, tip(harness.components().config().authRepo(), "refs/heads/SYNC-1"),
                "authoritative ticket branch must be fast-forwarded too");
    }

    @Test
    void dirty_clone_is_skipped_when_not_allowed_and_replayed_when_allowed() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"SYNC-2\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("SYNC-2");
        advanceMain(1);
        Files.writeString(clone.resolve("wip.txt"), "half-done agent work\n");

        HttpResponse<String> refused = post("/api/tickets/SYNC-2/sync-base", "{\"allow_dirty\":false}");
        assertEquals(200, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("\"status\":\"skipped\""), refused.body());
        assertTrue(refused.body().contains("未提交改动"), refused.body());
        assertEquals("half-done agent work\n", Files.readString(clone.resolve("wip.txt")),
                "skip must leave the worktree untouched");

        HttpResponse<String> synced = post("/api/tickets/SYNC-2/sync-base", "{\"allow_dirty\":true}");
        assertEquals(200, synced.statusCode(), synced.body());
        assertTrue(synced.body().contains("\"status\":\"synced\""), synced.body());
        assertTrue(synced.body().contains("\"behind\":1"), synced.body());
        assertEquals("half-done agent work\n", Files.readString(clone.resolve("wip.txt")),
                "uncommitted work must survive the sync");
        String mainTip = tip(harness.components().config().authRepo(), "refs/heads/main");
        assertEquals(mainTip, tip(clone, "HEAD"));
    }

    @Test
    void content_identical_divergence_is_healed_without_touching_worktree() throws Exception {
        // The T-113 incident shape: the clone holds a local recovery commit whose tree exactly
        // equals the base tip tree (histories differ, content does not). A soft re-point must fix
        // the BASE_STALE without moving a single worktree byte.
        post("/api/tickets", "{\"ticket_no\":\"SYNC-3\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("SYNC-3");
        var git = harness.components().git();
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost");
        git.must(clone, env, "commit", "--allow-empty", "-m", "local recovery commit");
        Files.writeString(clone.resolve("wip.txt"), "untracked scratch\n");
        String localHead = tip(clone, "HEAD");
        advanceMainEmpty(1);

        HttpResponse<String> res = post("/api/tickets/SYNC-3/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"status\":\"healed\""), res.body());

        String mainTip = tip(harness.components().config().authRepo(), "refs/heads/main");
        assertEquals(mainTip, tip(clone, "HEAD"), "clone must sit on the base tip");
        assertEquals(mainTip, tip(harness.components().config().authRepo(), "refs/heads/SYNC-3"),
                "authoritative branch must fast-forward alongside");
        assertFalse(tip(clone, "HEAD").equals(localHead), "the divergent commit is left behind");
        assertEquals("untracked scratch\n", Files.readString(clone.resolve("wip.txt")),
                "worktree must be untouched by the soft re-point");
    }

    @Test
    void terminal_ticket_is_refused() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"SYNC-4\",\"title\":\"t\"}");
        assertEquals(200, patch("/api/tickets/SYNC-4",
                "{\"stage\":\"CANCELLED\",\"reason\":\"不再需要\"}").statusCode());

        HttpResponse<String> res = post("/api/tickets/SYNC-4/sync-base", "{}");
        assertTrue(res.statusCode() >= 400, "terminal tickets have no live clone semantics: " + res.body());
        assertTrue(res.body().contains("restart"), res.body());
    }

    @Test
    void clone_lagging_its_own_published_branch_fast_forwards() throws Exception {
        // The T-113 round-2 incident shape: a publish advanced the authoritative ticket branch
        // while the clone stayed behind, and base does not contain the published commit either
        // (the human merges in the workspace later). The sync must fast-forward the clone onto
        // the branch tip and leave the branch alone.
        post("/api/tickets", "{\"ticket_no\":\"SYNC-6\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("SYNC-6");
        var cfg = harness.components().config();
        var git = harness.components().git();

        // Simulate the publish: a new commit lands on the authoritative ticket branch only.
        Path work = harness.root().resolve("publish-sim-" + System.nanoTime());
        git.must(gate.domain.git.RepoRef.of(harness.root()), "clone", "--quiet", "--single-branch",
                "--branch", "SYNC-6", cfg.authRepo().toString(), work.toString());
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost");
        Files.writeString(work.resolve("published.txt"), "round 2 content\n");
        git.must(work, env, "add", "-A");
        git.must(work, env, "commit", "-m", "T-113 会话：新增todowrite相关显示 (round 2)");
        git.must(gate.domain.git.RepoRef.of(cfg.authRepo()), "fetch", work.toAbsolutePath().toString(),
                "+refs/heads/SYNC-6:refs/heads/SYNC-6");

        Files.writeString(clone.resolve("wip.txt"), "post-publish scratch\n");
        HttpResponse<String> res = post("/api/tickets/SYNC-6/sync-base", "{\"allow_dirty\":true}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"status\":\"synced\""), res.body());
        assertTrue(res.body().contains("\"branch_moved\":false"), res.body());

        String branchTip = tip(cfg.authRepo(), "refs/heads/SYNC-6");
        assertEquals(branchTip, tip(clone, "HEAD"), "clone must sit on its own branch tip");
        assertEquals("round 2 content\n", Files.readString(clone.resolve("published.txt")),
                "the published content must be present after the fast-forward");
        assertEquals("post-publish scratch\n", Files.readString(clone.resolve("wip.txt")),
                "uncommitted work must survive the fast-forward");
    }

    @Test
    void publish_fast_forwards_the_clone_to_the_published_commit() throws Exception {
        // T-113 round-2 incident, end to end: after a successful publish the clone must sit on
        // the published commit (diff tab clears), not linger one commit behind with the already
        // reviewed work showing as a phantom working diff.
        post("/api/tickets", "{\"ticket_no\":\"SYNC-7\",\"title\":\"t\"}");
        Path clone = harness.components().config().clonesRoot().resolve("SYNC-7");
        Files.writeString(clone.resolve("published.txt"), "round content\n");
        assertEquals(200, post("/api/tickets/SYNC-7/presubmit", "").statusCode());

        String reviewTask = startTask("POST", "/api/tickets/SYNC-7/review", "{\"human_pass\":true}");
        waitForStatus(reviewTask, "SUCCEEDED");
        String publishTask = startTask("POST", "/api/tickets/SYNC-7/publish", "{}");
        waitForStatus(publishTask, "SUCCEEDED");
        String commitSha = extract(taskResultJson(publishTask), "commit_sha");

        assertEquals(commitSha, tip(clone, "HEAD"),
                "clone must be fast-forwarded onto the published commit");
        var status = harness.components().git().run(
                gate.domain.git.RepoRef.of(clone), "status", "--porcelain");
        assertEquals("", status.stdout(),
                "the agent's captured work is inside the published commit; worktree must be clean");
        assertEquals("round content\n", Files.readString(clone.resolve("published.txt")));
    }

    @Test
    void up_to_date_clone_is_a_reported_noop() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"SYNC-5\",\"title\":\"t\"}");
        HttpResponse<String> res = post("/api/tickets/SYNC-5/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"status\":\"up_to_date\""), res.body());
        assertTrue(res.body().contains("\"behind\":0"), res.body());
    }

    @Test
    void workspace_commit_imported_into_mirror_then_synced_into_clone() throws Exception {
        // The T-125 shape, ongoing half: the human commits in the registered workspace, the
        // gate-owned mirror knows nothing of it, and the old sync reported up_to_date forever.
        // The sync must first import the workspace base into the mirror, then land it in the
        // clone.
        String projectId = registerWorkspaceProject("WSIMP", true);
        String ticketNo = createProjectTicket(projectId, "WSIMP-1");
        Path workspace = Path.of(projectWorkspace(projectId));
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);

        // The human adds a doc directory in the workspace and commits it on the base branch.
        Files.createDirectories(workspace.resolve("doc"));
        Files.writeString(workspace.resolve("doc").resolve("guide.md"), "导入的文档\n");
        commitInWorkspace(workspace, "建立doc文档库");

        HttpResponse<String> res = post("/api/tickets/" + ticketNo + "/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"import_kind\":\"fast_forwarded\""), res.body());
        assertTrue(res.body().contains("\"status\":\"synced\""), res.body());
        assertTrue(res.body().contains("\"behind\":1"), res.body());
        assertEquals("导入的文档\n", Files.readString(clone.resolve("doc").resolve("guide.md")),
                "the workspace commit must reach the clone via the mirror import");

        // A second sync without new workspace work stays up_to_date (no double import).
        HttpResponse<String> again = post("/api/tickets/" + ticketNo + "/sync-base", "{}");
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"import_kind\":\"up_to_date\""), again.body());
        assertTrue(again.body().contains("\"status\":\"up_to_date\""), again.body());
    }

    @Test
    void unrelated_workspace_history_is_adopted_and_seed_clone_replanted() throws Exception {
        // The full T-125 incident: the ticket was cut while the workspace had no commits yet, so
        // mirror base, ticket branch and clone all sit on the empty gate seed. The human then
        // builds real (unrelated) history in the workspace — the sync must adopt it as the new
        // base and replant the seed-only branch and clone onto it.
        String projectId = registerWorkspaceProject("WSADOPT", false);
        String ticketNo = createProjectTicket(projectId, "WSADOPT-1");
        Path workspace = Path.of(projectWorkspace(projectId));
        Path projectMirror = Path.of(projectAuthRepo(projectId));
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        var git = harness.components().git();
        String seedTip = tip(projectMirror, "refs/heads/main");
        assertEquals(seedTip, tip(clone, "HEAD"), "the fresh clone starts on the gate seed");

        // The human turns the workspace into a real repository with its own root.
        git.must(gate.domain.git.RepoRef.of(workspace), "init", "-b", "main");
        Files.writeString(workspace.resolve("doc-base.md"), "建立doc文档库\n");
        commitInWorkspace(workspace, "建立doc文档库");

        HttpResponse<String> res = post("/api/tickets/" + ticketNo + "/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"import_kind\":\"adopted\""), res.body());
        assertTrue(res.body().contains("\"status\":\"replanted\""), res.body());

        String wsTip = tip(workspace, "refs/heads/main");
        assertEquals(wsTip, tip(projectMirror, "refs/heads/main"),
                "the mirror base must adopt the workspace lineage");
        assertEquals(wsTip, tip(projectMirror, "refs/heads/" + ticketNo),
                "the seed-only authoritative ticket branch is replanted alongside");
        assertEquals(wsTip, tip(clone, "HEAD"),
                "the seed clone must be replanted onto the adopted base");
        assertEquals("建立doc文档库\n", Files.readString(clone.resolve("doc-base.md")),
                "the adopted workspace content must be present in the clone");
    }

    @Test
    void diverged_workspace_history_is_never_imported() throws Exception {
        // Adoption is reserved for the pure-seed baseline: once real history sits in the mirror,
        // an unrelated workspace line must be refused, not force-merged (fail-closed).
        String projectId = registerWorkspaceProject("WSDIV", true);
        String ticketNo = createProjectTicket(projectId, "WSDIV-1");
        Path workspace = Path.of(projectWorkspace(projectId));
        Path projectMirror = Path.of(projectAuthRepo(projectId));
        var git = harness.components().git();
        String mirrorBefore = tip(projectMirror, "refs/heads/main");

        // The workspace is wiped and rebuilt on an independent root.
        deleteRecursively(workspace.resolve(".git"));
        git.must(gate.domain.git.RepoRef.of(workspace), "init", "-b", "main");
        Files.writeString(workspace.resolve("elsewhere.md"), "独立演化的工作区提交\n");
        commitInWorkspace(workspace, "独立演化的工作区提交");

        HttpResponse<String> res = post("/api/tickets/" + ticketNo + "/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"import_kind\":\"skipped\""), res.body());
        assertTrue(res.body().contains("历史分叉"), res.body());
        assertEquals(mirrorBefore, tip(projectMirror, "refs/heads/main"),
                "the mirror base must stay untouched on refusal");
    }

    @Test
    void project_registration_adopts_existing_workspace_history() throws Exception {
        // Registering a project against a pre-existing repository must adopt its history right
        // away, so the very first ticket clone is cut from the real baseline, not the seed.
        String projectId = registerWorkspaceProject("WSREG", true);
        Path workspace = Path.of(projectWorkspace(projectId));
        Path projectMirror = Path.of(projectAuthRepo(projectId));

        assertEquals(tip(workspace, "refs/heads/main"), tip(projectMirror, "refs/heads/main"),
                "registration must adopt the workspace history into the mirror");
    }

    /**
     * Registers a project over a fresh workspace directory, optionally seeded with an independent
     * git history (root commit holding {@code doc-base.md}) before registration — the shape of a
     * pre-existing human repository.
     */
    private String registerWorkspaceProject(String name, boolean preSeedHistory) throws Exception {
        Path workspace = harness.root().resolve("ws-" + name.toLowerCase() + "-" + System.nanoTime());
        Files.createDirectories(workspace);
        var git = harness.components().git();
        if (preSeedHistory) {
            git.must(gate.domain.git.RepoRef.of(workspace), "init", "-b", "main");
            Files.writeString(workspace.resolve("doc-base.md"), "项目基线内容\n");
            commitInWorkspace(workspace, "初始化源仓库");
        }
        HttpResponse<String> res = post("/api/projects", "{\"name\":\"" + name
                + "\",\"workspace_path\":\"" + workspace.toString().replace('\\', '/') + "\"}");
        assertEquals(201, res.statusCode(), res.body());
        return extract(res.body(), "id");
    }

    private String projectAuthRepo(String projectId) throws Exception {
        HttpResponse<String> res = get("/api/projects");
        var projects = (java.util.List<?>)
                ((java.util.Map<?, ?>) gate.application.util.MiniJson.parse(res.body().trim()))
                        .get("projects");
        for (Object o : projects) {
            var p = (java.util.Map<?, ?>) o;
            if (projectId.equals(String.valueOf(p.get("id")))) {
                return String.valueOf(p.get("auth_repo"));
            }
        }
        throw new AssertionError("project not found: " + projectId);
    }

    private String projectWorkspace(String projectId) throws Exception {
        HttpResponse<String> res = get("/api/projects");
        var projects = (java.util.List<?>)
                ((java.util.Map<?, ?>) gate.application.util.MiniJson.parse(res.body().trim()))
                        .get("projects");
        for (Object o : projects) {
            var p = (java.util.Map<?, ?>) o;
            if (projectId.equals(String.valueOf(p.get("id")))) {
                return String.valueOf(p.get("workspace_path"));
            }
        }
        throw new AssertionError("project not found: " + projectId);
    }

    private String createProjectTicket(String projectId, String ticketNo) throws Exception {
        HttpResponse<String> res = post("/api/projects/" + projectId + "/tickets",
                "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"t\"}");
        assertEquals(201, res.statusCode(), res.body());
        return ticketNo;
    }

    private void commitInWorkspace(Path workspace, String message) {
        var git = harness.components().git();
        git.must(gate.domain.git.RepoRef.of(workspace), "add", "-A");
        git.must(gate.domain.git.RepoRef.of(workspace), "-c", "user.name=human",
                "-c", "user.email=human@localhost", "commit", "-m", message);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    // git object files are read-only on Windows; force-writable before delete
                    p.toFile().setWritable(true);
                    Files.delete(p);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }


    /**
     * Adds {@code n} empty commits to the authoritative main branch. The commit is built in a
     * throwaway clone and landed with a server-side {@code git fetch} into the bare repo — the
     * installed pre-receive hook rejects plain pushes without an approval, but a fetch writes the
     * ref directly (same bootstrap nature as ensureSeeded).
     */
    /** Adds {@code n} EMPTY commits to main — the tree stays identical (heal-path scenarios). */
    private void advanceMainEmpty(int n) {
        try {
            advanceMainChecked(n, true);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void advanceMain(int n) {
        try {
            advanceMainChecked(n, false);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void advanceMainChecked(int n, boolean emptyCommits) throws java.io.IOException {
        var cfg = harness.components().config();
        var git = harness.components().git();
        Path work = harness.root().resolve("main-advance-" + n + "-" + System.nanoTime());
        git.must(gate.domain.git.RepoRef.of(harness.root()), "clone", "--quiet",
                cfg.authRepo().toString(), work.toString());
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost");
        for (int i = 0; i < n; i++) {
            if (!emptyCommits) {
                Files.writeString(work.resolve("advance-" + i + ".txt"), "main moves on\n");
                git.must(work, env, "add", "-A");
            }
            git.must(work, env, "commit", "--allow-empty", "-m", "advance " + i);
        }
        git.must(gate.domain.git.RepoRef.of(cfg.authRepo()), "fetch", work.toAbsolutePath().toString(),
                "+refs/heads/main:refs/heads/main");
    }

    private String tip(Path repo, String ref) {
        return harness.components().git().line(
                gate.domain.git.RepoRef.of(repo), "rev-parse", ref).trim();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\":\"");
        if (i < 0) {
            throw new AssertionError("field " + field + " missing in " + json);
        }
        int start = i + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }

    private String startTask(String method, String path, String body) throws Exception {
        HttpResponse<String> res = post(path, body);
        assertEquals(202, res.statusCode(), res.body());
        return taskId(res.body());
    }

    private void waitForStatus(String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            HttpResponse<String> res = get("/api/tasks/" + taskId);
            assertEquals(200, res.statusCode(), res.body());
            String status = stringField(res.body(), "status");
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status) && !"FAILED".equals(expected)) {
                fail("task " + taskId + " failed unexpectedly: " + res.body());
            }
            Thread.sleep(50);
        }
        fail("timed out waiting for task " + taskId + " to reach " + expected);
    }

    @SuppressWarnings("unchecked")
    private String taskResultJson(String taskId) throws Exception {
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(
                get("/api/tasks/" + taskId).body().trim());
        return String.valueOf(m.get("result_json"));
    }

    @SuppressWarnings("unchecked")
    private static String taskId(String responseBody) {
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(responseBody.trim());
        return String.valueOf(m.get("task_id"));
    }

    @SuppressWarnings("unchecked")
    private static String stringField(String body, String field) {
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(body.trim());
        return String.valueOf(m.get(field));
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
