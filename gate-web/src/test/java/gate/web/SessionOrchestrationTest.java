package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.ports.session.AgentSessionPort;
import gate.ports.infra.Clock;
import gate.ports.store.SessionRepository;
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
import org.junit.jupiter.api.Tag;

/**
 * S4 session orchestration via HTTP (执行文档-后端-web §9.5 A18, §10 S4). Uses a fake
 * {@link AgentSessionPort} so the test does not depend on a real claude binary.
 */
@Tag("slow")
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

    @Test
    void send_with_attachments_validates_and_passes_through() throws Exception {
        HttpResponse<String> cfg = post("/api/agent-configs", """
                {"id":"claude-att","name":"Claude Att","cli":"CLAUDE","provider_id":"manual",
                 "model":"claude-test","extra_flags":[],"description":"test"}
                """);
        assertEquals(201, cfg.statusCode(), cfg.body());
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"SESS-ATT\",\"title\":\"att\"}")
                .statusCode());
        HttpResponse<String> created = post("/api/tickets/SESS-ATT/sessions",
                "{\"agent_config_id\":\"claude-att\"}");
        assertEquals(201, created.statusCode(), created.body());
        String sid = sessionId(created.body());

        // 非法 mime 拒绝（USAGE）：仅图片附件可发。
        assertTrue(post("/api/sessions/" + sid + "/messages", """
                {"message":"x","attachments":[{"mime":"application/pdf","data_base64":"AA=="}]}
                """).statusCode() >= 400);
        // 缺 data_base64 拒绝。
        assertTrue(post("/api/sessions/" + sid + "/messages",
                "{\"message\":\"x\",\"attachments\":[{\"mime\":\"image/png\"}]}").statusCode() >= 400);

        // 合法图片附件：透传到端口，正文原样（引用文本由前端负责）。
        HttpResponse<String> ok = post("/api/sessions/" + sid + "/messages", """
                {"message":"看图","attachments":[{"filename":"a.png","mime":"image/png","data_base64":"aGVsbG8="}]}
                """);
        assertEquals(202, ok.statusCode(), ok.body());
        AgentSessionPort.SendRequest captured = FakeAgentSessionPort.lastSent;
        org.junit.jupiter.api.Assertions.assertNotNull(captured, "send must reach the port");
        assertEquals(sid, captured.sessionId());
        assertEquals("看图", captured.message());
        assertEquals(1, captured.attachments().size());
        assertEquals("image/png", captured.attachments().get(0).mime());
        assertEquals("aGVsbG8=", captured.attachments().get(0).dataBase64());
        assertEquals("a.png", captured.attachments().get(0).filename());
    }

    @Test
    void session_create_without_prompt_creates_idle_session() throws Exception {
        HttpResponse<String> cfg = post("/api/agent-configs", """
                {"id":"claude-idle","name":"Claude Idle","cli":"CLAUDE","provider_id":"manual",
                 "model":"claude-test","extra_flags":[],"description":"test"}
                """);
        assertEquals(201, cfg.statusCode(), cfg.body());
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"SESS-2\",\"title\":\"idle\"}").statusCode());

        // No initial_prompt -> idle session: created, ACTIVE, no first message sent.
        HttpResponse<String> created = post("/api/tickets/SESS-2/sessions",
                "{\"agent_config_id\":\"claude-idle\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"status\":\"ACTIVE\""), created.body());
        assertTrue(created.body().contains("\"title\":null"), created.body());
        assertTrue(created.body().contains("\"archived\":false"), created.body());
        String sid = sessionId(created.body());

        HttpResponse<String> history = get("/api/sessions/" + sid + "/messages");
        assertEquals(200, history.statusCode(), history.body());
        assertEquals(0, harness.components().sessionRepository().findMessages(sid).size(),
                "idle session must not have a first message");
    }

    @Test
    void session_patch_and_delete_are_routed_over_http() throws Exception {
        assertEquals(201, post("/api/agent-configs", """
                {"id":"claude-dispatch","name":"Claude Dispatch","cli":"CLAUDE","provider_id":"manual",
                 "model":"claude-test","extra_flags":[],"description":"test"}
                """).statusCode());
        assertEquals(201, post("/api/tickets",
                "{\"ticket_no\":\"SESS-HTTP\",\"title\":\"dispatch\"}").statusCode());

        HttpResponse<String> created = post("/api/tickets/SESS-HTTP/sessions",
                "{\"agent_config_id\":\"claude-dispatch\",\"initial_prompt\":\"go\"}");
        assertEquals(201, created.statusCode(), created.body());
        String sid = sessionId(created.body());

        // PATCH through the ApiRoutes dispatch: title + archived in one request.
        HttpResponse<String> patched = patch("/api/sessions/" + sid,
                "{\"title\":\"renamed by http\",\"archived\":true}");
        assertEquals(200, patched.statusCode(), patched.body());
        assertTrue(patched.body().contains("\"title\":\"renamed by http\""), patched.body());
        assertTrue(patched.body().contains("\"archived\":true"), patched.body());
        Session persisted = harness.components().sessionRepository().find(sid).orElseThrow();
        assertEquals("renamed by http", persisted.title());
        assertTrue(persisted.archived());

        // An empty PATCH body is a USAGE error through the same dispatch.
        HttpResponse<String> badPatch = patch("/api/sessions/" + sid, "{}");
        assertTrue(badPatch.statusCode() >= 400, badPatch.body());

        // DELETE through the dispatch: aborts (session is ACTIVE), drops messages + row.
        HttpResponse<String> deleted = delete("/api/sessions/" + sid);
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertTrue(deleted.body().contains("\"ok\":true"), deleted.body());
        assertTrue(fake.aborted.contains(sid), "ACTIVE session must be aborted before delete");
        assertTrue(harness.components().sessionRepository().find(sid).isEmpty());
        assertTrue(harness.components().sessionRepository().findMessages(sid).isEmpty());

        HttpResponse<String> gone = get("/api/sessions/" + sid);
        assertTrue(gone.statusCode() >= 400, "deleted session must not be readable: " + gone.body());
        assertTrue(delete("/api/sessions/" + sid).statusCode() >= 400,
                "deleting an already-deleted session must fail");
    }

    @Test
    void session_repository_roundtrips_title_and_archived() throws Exception {
        assertEquals(201, post("/api/agent-configs", """
                {"id":"claude-rt","name":"Claude RT","cli":"CLAUDE","provider_id":"manual",
                 "model":"claude-test","extra_flags":[],"description":"test"}
                """).statusCode());
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"SESS-4\",\"title\":\"rt\"}").statusCode());

        SessionRepository repo = harness.components().sessionRepository();
        Session s = new Session("sess-rt-1", "SESS-4", "claude-rt", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, null, rootClonePath(), -1, Instant.now(), null,
                SessionUsage.EMPTY, "roundtrip title", true);
        repo.insert(s);
        Session loaded = repo.find("sess-rt-1").orElseThrow();
        assertEquals("roundtrip title", loaded.title());
        assertTrue(loaded.archived());

        // update() must carry the metadata columns too.
        repo.update(loaded.withArchived(false));
        Session reloaded = repo.find("sess-rt-1").orElseThrow();
        assertEquals("roundtrip title", reloaded.title());
        assertFalse(reloaded.archived());

        // deleteMessages + delete removes the row set.
        repo.insertMessage(new SessionMessage("msg-rt-1", "sess-rt-1", Role.USER,
                "hello", List.of(), null, false, Instant.now()));
        assertEquals(1, repo.findMessages("sess-rt-1").size());
        repo.deleteMessages("sess-rt-1");
        repo.delete("sess-rt-1");
        assertTrue(repo.find("sess-rt-1").isEmpty());
        assertTrue(repo.findMessages("sess-rt-1").isEmpty());
    }

    private static String rootClonePath() {
        return java.nio.file.Path.of("clones", "SESS-4").toString();
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

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .DELETE().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static String sessionId(String body) {
        Map<String, Object> m = (Map<String, Object>) gate.application.util.MiniJson.parse(body.trim());
        return String.valueOf(m.get("id"));
    }

    /** In-memory fake session port backed by the real SessionRepository. */
    static final class FakeAgentSessionPort implements AgentSessionPort {

        /** Most recent send (attachment passthrough assertions read this). */
        static volatile SendRequest lastSent;

        private SessionRepository sessions;
        private Clock clock;
        private final List<String> aborted = new ArrayList<>();

        void bind(SessionRepository sessions, Clock clock) {
            this.sessions = sessions;
            this.clock = clock;
        }

        @Override
        public Session start(StartRequest request) {
            String id = UUID.randomUUID().toString();
            Session s = new Session(id, request.ticketNo(), request.agentConfigId(), AgentCli.CLAUDE,
                    SessionStatus.ACTIVE, "cli-" + id, request.clonePath(), -1, clock.now(), null,
                    SessionUsage.EMPTY, null, false);
            sessions.insert(s);
            // Mirrors the adapter contract: blank initial_prompt creates an idle session (no first send).
            if (request.initialPrompt() == null || request.initialPrompt().isBlank()) {
                return sessions.find(id).orElse(s);
            }
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), id, Role.USER,
                    request.initialPrompt(), List.of(), null, false, clock.now()));
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), id, Role.ASSISTANT,
                    "fake assistant", List.of(), new SessionUsage(1L, 2L, 3L), false, clock.now()));
            return sessions.find(id).orElse(s);
        }

        @Override
        public String sendMessage(SendRequest request) {
            lastSent = request;
            Session s = sessions.find(request.sessionId()).orElseThrow();
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), s.id(), Role.USER,
                    request.message(), List.of(), null, false, clock.now()));
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), s.id(), Role.ASSISTANT,
                    "fake assistant", List.of(), new SessionUsage(1L, 2L, 3L), false, clock.now()));
            return "fake-task-" + UUID.randomUUID();
        }

        @Override
        public void abort(String sessionId) {
            aborted.add(sessionId);
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
            // SSE stays open until a terminal chunk arrives; deliver done shortly after attach so
            // stream consumers (and the SSE assertion in session_lifecycle_over_http) terminate.
            Thread done = new Thread(() -> {
                try {
                    Thread.sleep(100);
                    listener.accept(new gate.domain.session.SessionStreamChunk.DoneChunk(
                            sessionId, "fake-message-id", clock.now()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            done.setDaemon(true);
            done.start();
            return done::interrupt;
        }

        @Override
        public void respondPermission(String sessionId, String permissionId, String response) {
            // Fake: nothing to forward; the test asserts the dispatch chain, not the transport.
        }

        @Override
        public List<gate.domain.session.PermissionRequest> pendingPermissions(String sessionId) {
            return List.of();
        }
    }
}
