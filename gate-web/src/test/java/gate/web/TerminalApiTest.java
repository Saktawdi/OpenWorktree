package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * 项目 → 终端 (web console): GET /api/projects/{id}/terminals enumerates the project's own
 * directories (workspace + per-ticket clones), and the /ws/terminal socket authenticates on its
 * first frame, spawns a shell in the requested directory and pipes output back.
 */
@Tag("slow")
class TerminalApiTest {

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
    void terminals_list_workspace_and_ticket_clones() throws Exception {
        Path ws = harness.root().resolve("term-ws");
        java.nio.file.Files.createDirectories(ws);
        String id = registerProject("Term", ws);

        HttpResponse<String> before = get("/api/projects/" + id + "/terminals");
        assertEquals(200, before.statusCode(), before.body());
        assertTrue(before.body().contains("\"type\":\"workspace\""), before.body());
        assertTrue(before.body().contains(json(ws.toString())), before.body());
        assertTrue(before.body().contains("工作区"), before.body());

        HttpResponse<String> created = post("/api/projects/" + id + "/tickets",
                "{\"ticket_no\":\"TERM-1\",\"title\":\"term ticket\"}");
        assertEquals(201, created.statusCode(), created.body());

        HttpResponse<String> after = get("/api/projects/" + id + "/terminals");
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(after.body().contains("TERM-1"), after.body());
        assertTrue(after.body().contains("term ticket"), after.body());
        assertTrue(after.body().contains("\"type\":\"clone\""), after.body());
    }

    @Test
    void terminals_endpoint_rejects_unknown_project() throws Exception {
        HttpResponse<String> res = get("/api/projects/nope/terminals");
        assertTrue(res.statusCode() >= 400, res.body());
    }

    @Test
    void terminal_socket_rejects_invalid_token_then_serves_a_shell() throws Exception {
        Path ws = harness.root().resolve("term-shell-ws");
        java.nio.file.Files.createDirectories(ws);
        String id = registerProject("Shell", ws);

        // First frame carries the HUMAN token (Javalin 5 has no upgrade-stage auth hook).
        ConcurrentLinkedQueue<String> badAuth = new ConcurrentLinkedQueue<>();
        WebSocket bad = openSocket(badAuth);
        send(bad, startFrame("not-a-token", id, ws));
        assertTrue(awaitFrame(badAuth, "error", 20),
                "invalid token must yield an error frame, got: " + badAuth);
        bad.abort();

        ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
        WebSocket socket = openSocket(frames);
        send(socket, startFrame(token, id, ws));
        assertTrue(awaitFrame(frames, "started", 20),
                "valid start must be acknowledged, got: " + frames);

        // Directory outside the project allowlist must be refused (no shell spawned).
        ConcurrentLinkedQueue<String> foreign = new ConcurrentLinkedQueue<>();
        WebSocket stranger = openSocket(foreign);
        send(stranger, "{\"op\":\"start\",\"token\":\"" + token + "\",\"project\":\"" + id
                + "\",\"dir\":\"" + json(harness.root().resolve("somewhere-else").toString()) + "\"}");
        assertTrue(awaitFrame(foreign, "error", 20),
                "foreign directory must be refused, got: " + foreign);
        stranger.abort();

        // A real shell: echo round-trips through the piped console (cmd.exe / bash).
        send(socket, "{\"op\":\"input\",\"data\":\"echo gate-term-ok\\r\\n\"}");
        assertTrue(awaitFrame(frames, "gate-term-ok", 20),
                "shell output must round-trip, got: " + frames);
        socket.abort();
    }

    /* ─── ws plumbing ─── */

    private WebSocket openSocket(ConcurrentLinkedQueue<String> sink) throws Exception {
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws/terminal"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                sink.add(data.toString());
                                ws.request(1);
                                return CompletableFuture.completedFuture(null);
                            }
                        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void send(WebSocket socket, String json) {
        socket.sendText(json, true).join();
    }

    private String startFrame(String tokenValue, String projectId, Path dir) {
        return "{\"op\":\"start\",\"token\":\"" + tokenValue + "\",\"project\":\"" + projectId
                + "\",\"dir\":\"" + json(dir.toString()) + "\"}";
    }

    /** Polls the sink until a frame containing the needle arrives (ops and data are both text). */
    private static boolean awaitFrame(ConcurrentLinkedQueue<String> frames, String needle, int seconds)
            throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String frame : frames) {
                if (frame.contains(needle)) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    /* ─── http helpers (same shape as ProjectRepoViewApiTest) ─── */

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
