package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.ports.AgentSessionPort;
import gate.ports.Clock;
import gate.ports.SessionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S4 session orchestration via HTTP (执行文档-后端-web §9.5 A18, §10 S4). Uses a fake
 * {@link AgentSessionPort} so the test does not depend on a real claude binary.
 */
class SessionOrchestrationTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private FakeAgentSessionPort fake;

    @BeforeEach
    void setUp() {
        fake = new FakeAgentSessionPort();
        harness = new WebHarness("git", "127.0.0.1", fake);
        fake.bind(harness.components().sessionRepository(), harness.components().clock());
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
    void session_lifecycle_over_http() throws Exception {
        // AgentConfig + ticket prerequisites.
        HttpResponse<String> cfg = post("/api/agent-configs", """
                {"id":"claude-sess","name":"Claude Sess","cli":"CLAUDE","provider_id":"manual",
                 "model":"claude-test","extra_flags":[],"description":"test"}
                """);
        assertEquals(201, cfg.statusCode(), cfg.body());
        HttpResponse<String> ticket = post("/api/tickets",
                "{\"ticket_no\":\"SESS-1\",\"title\":\"session\"}");
        assertEquals(201, ticket.statusCode(), ticket.body());

        // Create a session bound to the ticket.
        HttpResponse<String> created = post("/api/tickets/SESS-1/sessions",
                "{\"agent_config_id\":\"claude-sess\",\"initial_prompt\":\"start\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"cli_session_id\":\"cli-"), created.body());
        String sid = sessionId(created.body());

        // List and detail.
        HttpResponse<String> list = get("/api/tickets/SESS-1/sessions");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains(sid), list.body());
        HttpResponse<String> detail = get("/api/sessions/" + sid);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"status\":\"ACTIVE\""), detail.body());

        // Send a message -> 202 task id, history grows.
        HttpResponse<String> send = post("/api/sessions/" + sid + "/messages",
                "{\"message\":\"hello agent\"}");
        assertEquals(202, send.statusCode(), send.body());
        assertTrue(send.body().contains("\"task_id\""), send.body());

        HttpResponse<String> history = get("/api/sessions/" + sid + "/messages");
        assertEquals(200, history.statusCode(), history.body());
        assertTrue(history.body().contains("hello agent"), history.body());
        assertTrue(history.body().contains("fake assistant"), history.body());

        // SSE replays messages.
        HttpResponse<String> events = get("/api/sessions/" + sid + "/events?token=" + token);
        assertEquals(200, events.statusCode(), events.body());
        assertTrue(events.body().contains("event: message"), events.body());

        // Abort.
        HttpResponse<String> abort = post("/api/sessions/" + sid + "/abort", "{}");
        assertEquals(200, abort.statusCode(), abort.body());
        HttpResponse<String> after = get("/api/sessions/" + sid);
        assertTrue(after.body().contains("\"status\":\"ABORTED\""), after.body());
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
    private static String sessionId(String body) {
        Map<String, Object> m = (Map<String, Object>) gate.application.MiniJson.parse(body.trim());
        return String.valueOf(m.get("id"));
    }

    /** In-memory fake session port backed by the real SessionRepository. */
    static final class FakeAgentSessionPort implements AgentSessionPort {

        private SessionRepository sessions;
        private Clock clock;

        void bind(SessionRepository sessions, Clock clock) {
            this.sessions = sessions;
            this.clock = clock;
        }

        @Override
        public Session start(StartRequest request) {
            String id = UUID.randomUUID().toString();
            Session s = new Session(id, request.ticketNo(), request.agentConfigId(), AgentCli.CLAUDE,
                    SessionStatus.ACTIVE, "cli-" + id, request.clonePath(), -1, clock.now(), null,
                    SessionUsage.EMPTY);
            sessions.insert(s);
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), id, Role.USER,
                    request.initialPrompt() == null ? "" : request.initialPrompt(), List.of(), null,
                    false, clock.now()));
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), id, Role.ASSISTANT,
                    "fake assistant", List.of(), new SessionUsage(1L, 2L, 3L), false, clock.now()));
            return sessions.find(id).orElse(s);
        }

        @Override
        public String sendMessage(SendRequest request) {
            Session s = sessions.find(request.sessionId()).orElseThrow();
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), s.id(), Role.USER,
                    request.message(), List.of(), null, false, clock.now()));
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), s.id(), Role.ASSISTANT,
                    "fake assistant", List.of(), new SessionUsage(1L, 2L, 3L), false, clock.now()));
            return "fake-task-" + UUID.randomUUID();
        }

        @Override
        public void abort(String sessionId) {
            sessions.find(sessionId).ifPresent(s ->
                    sessions.update(s.withStatus(SessionStatus.ABORTED).withFinishedAt(clock.now())));
        }

        @Override
        public List<SessionMessage> getHistory(String sessionId) {
            return sessions.findMessages(sessionId);
        }

        @Override
        public Stream<SessionEvent> streamEvents(String sessionId) {
            List<SessionEvent> events = new ArrayList<>();
            for (SessionMessage m : sessions.findMessages(sessionId)) {
                events.add(new SessionEvent(sessionId, m, "message"));
            }
            return events.stream();
        }

        @Override
        public AutoCloseable attachListener(String sessionId, java.util.function.Consumer<gate.domain.session.SessionStreamChunk> listener) {
            return () -> {};
        }
    }
}
