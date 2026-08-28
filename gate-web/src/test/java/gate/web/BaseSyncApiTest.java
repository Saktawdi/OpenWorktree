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
 * T-118 基座同步 API：POST /api/tickets/{no}/sync-base 把落后主分支的工单 clone 与权威分支快进到
 * base tip，未提交改动按 allow_dirty 语义跳过或 stash 重放；审查门禁期与终态工单拒绝同步。
 */
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
        assertEquals(200, patch("/api/tickets/SYNC-4", "{\"stage\":\"CANCELLED\"}").statusCode());

        HttpResponse<String> res = post("/api/tickets/SYNC-4/sync-base", "{}");
        assertTrue(res.statusCode() >= 400, "terminal tickets have no live clone semantics: " + res.body());
        assertTrue(res.body().contains("restart"), res.body());
    }

    @Test
    void up_to_date_clone_is_a_reported_noop() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"SYNC-5\",\"title\":\"t\"}");
        HttpResponse<String> res = post("/api/tickets/SYNC-5/sync-base", "{}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"status\":\"up_to_date\""), res.body());
        assertTrue(res.body().contains("\"behind\":0"), res.body());
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
