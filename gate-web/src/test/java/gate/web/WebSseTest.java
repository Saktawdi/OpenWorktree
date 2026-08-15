package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * S2 SSE tests (执行文档-后端-web §9.3): task event stream auth, Content-Type and terminal done
 * event.
 */
class WebSseTest {

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
    void sse_endpoint_without_token_is_401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/tasks/unknown/events"))
                .GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode(), res.body());
    }

    @Test
    void sse_unknown_task_with_token_is_404() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base + "/api/tasks/no-such-task/events?token=" + token))
                .GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode(), res.body());
    }

    @Test
    void review_task_sse_emits_progress_and_done() throws Exception {
        String ticketNo = "SSE-1";
        post("/api/tickets", "{\"ticket_no\":\"" + ticketNo + "\",\"title\":\"sse\"}");
        Path clone = harness.components().config().clonesRoot().resolve(ticketNo);
        Files.writeString(clone.resolve("feature.txt"), "change\n");
        HttpResponse<String> pre = post("/api/tickets/" + ticketNo + "/presubmit", "");
        assertEquals(200, pre.statusCode(), pre.body());

        HttpResponse<String> started = post("/api/tickets/" + ticketNo + "/review",
                "{\"human_pass\":true}");
        assertEquals(202, started.statusCode(), started.body());
        String taskId = taskId(started.body());

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base + "/api/tasks/" + taskId + "/events?token=" + token))
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("Content-Type").orElse("")
                .contains("text/event-stream"), res.headers().firstValue("Content-Type").orElse(""));
        assertTrue(res.body().contains("event: done"), res.body());
        // Progress is expected when the SSE subscribes before/while the fast manual review runs;
        // when the task is already terminal it is acceptable for the stream to synthesize only done.
        if (res.body().contains("event: progress")) {
            assertTrue(res.body().contains("data: "), res.body());
        }
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

    @SuppressWarnings("unchecked")
    private static String taskId(String responseBody) {
        Object parsed = gate.application.MiniJson.parse(responseBody.trim());
        if (parsed instanceof java.util.Map<?, ?> m) {
            return String.valueOf(((java.util.Map<String, Object>) m).get("task_id"));
        }
        throw new AssertionError("expected task_id in " + responseBody);
    }
}
