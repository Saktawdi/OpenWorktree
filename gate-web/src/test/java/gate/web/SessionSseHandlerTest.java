package gate.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import gate.domain.session.AgentCli;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.SessionUsage;
import gate.ports.AgentSessionPort;
import gate.ports.SessionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Session SSE keep-alive tests (执行文档-后端-web §9.3): the stream must stay open beyond the old
 * 5s cap, emit `: ping` heartbeat frames while idle, and close promptly once done arrives.
 *
 * <p>Uses a dedicated {@link com.sun.net.httpserver.HttpServer} delegating to a
 * {@link SessionSseHandler} built with a short heartbeat interval, plus fakes for
 * {@link AgentSessionPort}/{@link SessionRepository}, so no real agent binary is involved.
 */
class SessionSseHandlerTest {

    private static final String SESSION_ID = "sse-keepalive-1";
    /** Short heartbeat so the test does not wait 15s per ping; well past the old 5s cap overall. */
    private static final long TEST_HEARTBEAT_MILLIS = 200L;

    private HttpServer server;
    private FakeSessionPort port;
    private AtomicBoolean handlerReturned;

    @BeforeEach
    void setUp() throws IOException {
        port = new FakeSessionPort();
        handlerReturned = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SessionSseHandler handler =
                new SessionSseHandler(port, new SingleSessionRepository(), TEST_HEARTBEAT_MILLIS);
        server.createContext("/", exchange -> {
            handler.handle(exchange, SESSION_ID);
            handlerReturned.set(true);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stream_survives_past_old_5s_cap_with_ping_heartbeats_until_done() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> res = client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/"))
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

        // 3. done ends the wait: frames flush, the handler returns and the response stream EOFs.
        long closeDeadline = System.nanoTime() + 5_000_000_000L;
        while (!handlerReturned.get() && System.nanoTime() < closeDeadline) {
            Thread.sleep(50);
        }
        assertTrue(handlerReturned.get(), "handler should return after done");
        reader.join(5_000);
        String body = received.toString();
        assertTrue(body.contains("event: token"), body);
        assertTrue(body.contains("text_delta"), body);
        assertTrue(body.contains("event: done"), body);
        assertFalse(reader.isAlive(), "response stream should be fully drained and closed");
    }

    private static int countPings(String body) {
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf(": ping", idx)) >= 0) {
            count++;
            idx += ": ping".length();
        }
        return count;
    }

    /** In-memory fake that only supports attachListener/emit; enough for the SSE handler. */
    static final class FakeSessionPort implements AgentSessionPort {

        private volatile Consumer<SessionStreamChunk> listener;

        void emit(SessionStreamChunk chunk) {
            Consumer<SessionStreamChunk> l = listener;
            if (l != null) {
                l.accept(chunk);
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
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionMessage> getHistory(String sessionId) {
            throw new UnsupportedOperationException();
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
    }

    /** Repository fake whose find() always reports the session as existing. */
    static final class SingleSessionRepository implements SessionRepository {

        private static final Session SESSION = new Session(
                SESSION_ID, "SSE-KEEP", "cfg-1", AgentCli.CLAUDE, SessionStatus.ACTIVE,
                "cli-1", "/tmp/clone", -1, Instant.EPOCH, null, SessionUsage.EMPTY, null, false);

        @Override
        public Optional<Session> find(String id) {
            return SESSION_ID.equals(id) ? Optional.of(SESSION) : Optional.empty();
        }

        @Override
        public List<Session> findByTicket(String ticketNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Session> findByAgentConfig(String agentConfigId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortOrphanedActive(Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertMessage(SessionMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionMessage> findMessages(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteMessages(String sessionId) {
            throw new UnsupportedOperationException();
        }
    }
}
