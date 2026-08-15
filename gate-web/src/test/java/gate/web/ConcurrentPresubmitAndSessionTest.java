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
 * S4 concurrency test (执行文档-后端-web §9.4): while a session/ticket lock is held, presubmit fails
 * fast with 422 instead of queueing.
 */
class ConcurrentPresubmitAndSessionTest {

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
    void presubmit_while_ticket_lock_held_returns_422() throws Exception {
        HttpResponse<String> created = post("/api/tickets",
                "{\"ticket_no\":\"LOCK-1\",\"title\":\"lock\"}");
        assertEquals(201, created.statusCode(), created.body());
        Path clone = harness.components().config().clonesRoot().resolve("LOCK-1");
        Files.writeString(clone.resolve("feature.txt"), "change\n");

        try (AutoCloseable ignored = harness.components().ticketLockManager().acquire("LOCK-1")) {
            HttpResponse<String> blocked = post("/api/tickets/LOCK-1/presubmit", "");
            assertEquals(422, blocked.statusCode(), blocked.body());
            assertTrue(blocked.body().contains("session in progress"), blocked.body());
        }

        HttpResponse<String> ok = post("/api/tickets/LOCK-1/presubmit", "");
        assertEquals(200, ok.statusCode(), ok.body());
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
