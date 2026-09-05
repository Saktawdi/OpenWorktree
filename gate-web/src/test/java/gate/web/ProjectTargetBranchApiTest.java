package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * 项目主分支（target_ref）的解析与编辑（T-118 基座同步的配套）：接入项目可显式指定主分支（master 等）、
 * 已有 git 工作区自动探测当前分支、编辑项目可改主分支；非法分支名拒绝。
 */
@Tag("slow")
class ProjectTargetBranchApiTest {

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
    void create_with_explicit_target_branch_inits_that_branch() throws Exception {
        Path ws = harness.root().resolve("ws-explicit-master");
        HttpResponse<String> res = post("/api/projects", "{\"name\":\"ExplicitProj\",\"workspace_path\":\""
                + json(ws.toString()) + "\",\"target_branch\":\"master\",\"init_git\":true}");
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"target_ref\":\"refs/heads/master\""), res.body());

        // The auto-initialized repo must have its unborn HEAD on master, not main.
        var git = harness.components().git();
        var head = git.run(gate.domain.git.RepoRef.of(ws), "symbolic-ref", "--short", "HEAD");
        assertTrue(head.ok(), head.stderrFirstLine());
        assertEquals("master", head.stdout().trim(), "git init must use the requested branch");
    }

    @Test
    void create_on_existing_master_workspace_autodetects_the_branch() throws Exception {
        Path ws = harness.root().resolve("ws-existing-master");
        Files.createDirectories(ws);
        var git = harness.components().git();
        git.run(ws, Map.of(), "init", "-b", "master");
        git.must(ws, Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost"),
                "commit", "--allow-empty", "-m", "seed");

        HttpResponse<String> res = post("/api/projects", "{\"name\":\"DetectProj\",\"workspace_path\":\""
                + json(ws.toString()) + "\"}");
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"target_ref\":\"refs/heads/master\""),
                "existing workspace on master must be detected: " + res.body());
    }

    @Test
    void update_project_changes_the_target_branch() throws Exception {
        Path ws = harness.root().resolve("ws-update");
        HttpResponse<String> created = post("/api/projects", "{\"name\":\"UpdateProj\",\"workspace_path\":\""
                + json(ws.toString()) + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"target_ref\":\"refs/heads/main\""), created.body());
        String id = extract(created.body(), "id");
        String authRepo = extract(created.body(), "auth_repo");

        HttpResponse<String> updated = put("/api/projects/" + id,
                "{\"name\":\"UpdateProj\",\"target_branch\":\"master\",\"tags\":[\"a\"]}");
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(updated.body().contains("\"target_ref\":\"refs/heads/master\""), updated.body());

        // The project's auth repo must become coherent with the new base: branch seeded, HEAD moved.
        var git = harness.components().git();
        var repo = gate.domain.git.RepoRef.of(Path.of(authRepo));
        assertTrue(git.run(repo, "rev-parse", "--verify", "refs/heads/master").ok(),
                "auth repo must have the new base branch seeded");
        var head = git.run(repo, "symbolic-ref", "--short", "HEAD");
        assertEquals("master", head.stdout().trim(), "auth repo HEAD must point at the new base");
    }

    @Test
    void invalid_branch_name_is_rejected() throws Exception {
        Path ws = harness.root().resolve("ws-invalid");
        HttpResponse<String> res = post("/api/projects", "{\"name\":\"InvalidProj\",\"workspace_path\":\""
                + json(ws.toString()) + "\",\"target_branch\":\"feat/nested\"}");
        assertTrue(res.statusCode() >= 400, "multi-segment branch must be rejected: " + res.body());
        assertTrue(res.body().contains("target_branch"), res.body());
    }

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

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
