package gate.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.session.AgentCli;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.SessionUsage;
import gate.domain.session.PermissionRequest;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.SessionRepository;
import gate.web.sse.SessionSseHandler;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * Session SSE keep-alive tests (执行文档-后端-web §9.3): the stream must stay open beyond the old
 * 5s cap, emit `: ping` heartbeat frames while idle, and close promptly once done arrives.
 */
@Tag("slow")
class SessionSseHandlerTest {

    private static final String SESSION_ID = "sse-keepalive-1";
    /** Short heartbeat so the test does not wait 15s per ping; well past the old 5s cap overall. */
    private static final long TEST_HEARTBEAT_MILLIS = 200L;

    private Javalin app;
    private FakeSessionPort port;
    private AtomicBoolean handlerReturned;

    @BeforeEach
    void setUp() throws IOException {
        port = new FakeSessionPort();
        handlerReturned = new AtomicBoolean(false);
        SessionSseHandler handler =
                new SessionSseHandler(port, new SingleSessionRepository(), TEST_HEARTBEAT_MILLIS);
        app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        app.sse("/events", client -> {
            handler.handle(client, SESSION_ID);
            handlerReturned.set(true);
        });
        app.start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void stream_survives_past_old_5s_cap_with_ping_heartbeats_until_done() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> res = client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + app.port() + "/events"))
                        .header("Accept", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertTrue(res.headers().firstValue("Content-Type").orElse("")
                .contains("text/event-stream"));

        // Background reader keeps accumulating the stream while the main thread asserts timing.
        StringBuilder received = new StringBuilder();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream in = res.body()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    received.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // server stopped; whatever was read is enough for the assertions
            }
        });
        reader.setDaemon(true);
        reader.start();

        // 1. Let more than the old 5s cap elapse with no terminal chunk: the connection must
        //    still be alive (no done event, handler still blocked) and ping frames must arrive.
        long deadline = System.nanoTime() + 5_500_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        String idle = received.toString();
        assertTrue(idle.contains(": ping"), idle);
        assertTrue(countPings(idle) >= 10,
                "expected >=10 heartbeats in 5.5s at " + TEST_HEARTBEAT_MILLIS + "ms, got "
                        + countPings(idle) + ": " + idle);
        assertFalse(idle.contains("event: done"), idle);
        assertFalse(handlerReturned.get(), "handler must still be waiting for done");

        // 2. Emit a live token chunk, then the terminal done chunk.
        port.emit(new SessionStreamChunk.ContentChunk(SESSION_ID, "hello", Instant.now()));
        port.emit(new SessionStreamChunk.DoneChunk(SESSION_ID, "msg-1", Instant.now()));

        long doneDeadline = System.nanoTime() + 2_000_000_000L;
        while (!handlerReturned.get() && System.nanoTime() < doneDeadline) {
            Thread.sleep(50);
        }
        assertTrue(handlerReturned.get(), "handler must return promptly after done chunk");

        // The received buffer must have seen the live token and the terminal done chunk.
        String full = received.toString();
        assertTrue(full.contains("event: token"), full);
        assertTrue(full.contains("hello"), full);
        assertTrue(full.contains("event: done"), full);
    }

    private static int countPings(String s) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(": ping", idx)) >= 0) {
            count++;
            idx += ": ping".length();
        }
        return count;
    }

    static final class FakeSessionPort implements AgentSessionPort {

        private Consumer<SessionStreamChunk> listener;

        void emit(SessionStreamChunk chunk) {
            if (listener != null) {
                listener.accept(chunk);
            }
        }

        @Override
        public Session start(StartRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sendMessage(SendRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort(String sessionId) {
        }

        @Override
        public List<SessionMessage> getHistory(String sessionId) {
            return List.of();
        }

        @Override
        public Stream<SessionEvent> streamEvents(String sessionId) {
            return Stream.empty();
        }

        @Override
        public AutoCloseable attachListener(String sessionId, Consumer<SessionStreamChunk> listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }

        @Override
        public void respondPermission(String sessionId, String permissionId, String response) {
        }

        @Override
        public List<PermissionRequest> pendingPermissions(String sessionId) {
            return List.of();
        }
    }

    static final class SingleSessionRepository implements SessionRepository {

        @Override
        public void insert(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Session> find(String id) {
            if (SESSION_ID.equals(id)) {
                return Optional.of(new Session(
                        SESSION_ID, "T-1", "cfg-1", AgentCli.CLAUDE, SessionStatus.ACTIVE,
                        "cli-sess", "/clone/path", -1,
                        Instant.now(), null, null, null, false));
            }
            return Optional.empty();
        }

        @Override
        public List<Session> findByTicket(String ticketNo) {
            return List.of();
        }

        @Override
        public List<Session> findByAgentConfig(String agentConfigId) {
            return List.of();
        }

        @Override
        public void update(Session session) {
        }

        @Override
        public void insertMessage(SessionMessage message) {
        }

        @Override
        public List<SessionMessage> findMessages(String sessionId) {
            return List.of();
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public void deleteMessages(String sessionId) {
        }
    }
}
