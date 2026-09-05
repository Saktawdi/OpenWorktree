package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.adapters.clock.SystemClock;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.ProcessRunnerImpl;
import gate.adapters.session.OpenCodeServeAdapter;
import gate.adapters.session.PortAllocator;
import gate.adapters.store.JdbcAgentConfigRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcSessionRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.QuestionRequest;
import gate.domain.session.Session;
import gate.domain.session.SessionStreamChunk;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * question 工具链路（opencode ≥ question.asked/replied/rejected）：asked 事件 → 卡片 chunk +
 * pending 快照；reply/reject 转发到 serve 的 /question/{id}/reply|reject；replied 事件清除待决。
 */
@Tag("slow")
class OpenCodeServeQuestionTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private JdbcSessionRepository sessions;
    private OpenCodeServeAdapter adapter;
    private final List<String> postPaths = new CopyOnWriteArrayList<>();
    private final List<String> postBodies = new CopyOnWriteArrayList<>();
    private volatile String pendingSnapshotJson = "[]";
    private final List<String> eventFrames = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-question-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        JdbcAgentConfigRepository agentConfigs = new JdbcAgentConfigRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("oc-question", "Question Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test"), now);
        new JdbcTicketRepository(jdbc).insert(new gate.domain.ticket.Ticket("OPEN-Q1", "t", "refs/heads/main",
                root.resolve("clone").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));
        Files.createDirectories(root.resolve("clone"));
        fakeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = fakeServer.getAddress().getPort();
        fakeServer.createContext("/health", this::health);
        fakeServer.createContext("/session", this::session);
        fakeServer.createContext("/question", this::question);
        fakeServer.createContext("/event", this::events);
        fakeServer.start();
        PortAllocator allocator = new PortAllocator(port, port);
        adapter = new OpenCodeServeAdapter(new ProcessRunnerImpl(root.resolve("proc")),
                agentConfigs, sessions, new JdbcTicketRepository(jdbc), null, null, ticketLocks,
                new SystemClock(), allocator, "", 60, null, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (adapter != null) {
            adapter.close();
        }
        if (fakeServer != null) {
            fakeServer.stop(0);
        }
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    private static final String ASKED_JSON = "{\"id\":\"que_1\",\"sessionID\":\"sess-1\","
            + "\"questions\":["
            + "{\"question\":\"上下文环的百分比如何计算？\",\"header\":\"上下文基准\","
            + "\"options\":[{\"label\":\"token 优先\",\"description\":\"按 usage tokens 计算\"},"
            + "{\"label\":\"字符估算\",\"description\":\"纯前端实现\"}],"
            + "\"multiple\":false,\"custom\":true},"
            + "{\"question\":\"颜色阈值怎么定？\",\"header\":\"颜色阈值\","
            + "\"options\":[{\"label\":\"60/85 分档\"}],\"multiple\":true}"
            + "],\"tool\":{\"messageID\":\"msg-7\",\"callID\":\"call-7\"}}";

    @Test
    void asked_emits_chunk_and_pending_snapshot() throws Exception {
        eventFrames.add(ASKED_JSON);
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-Q1", "oc-question", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();

        CountDownLatch asked = new CountDownLatch(1);
        AtomicReference<SessionStreamChunk.QuestionAskedChunk> askedChunk = new AtomicReference<>();
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.QuestionAskedChunk qa) {
                askedChunk.set(qa);
                asked.countDown();
            }
        });

        assertTrue(asked.await(5, TimeUnit.SECONDS), "asked chunk never arrived");
        SessionStreamChunk.QuestionAskedChunk qa = askedChunk.get();
        assertNotNull(qa);
        QuestionRequest r = qa.request();
        assertEquals("que_1", r.requestId());
        assertEquals(2, r.questions().size());
        assertEquals("上下文基准", r.questions().get(0).header());
        assertFalse(r.questions().get(0).multiple());
        assertTrue(r.questions().get(0).custom());
        assertEquals(2, r.questions().get(0).options().size());
        assertEquals("token 优先", r.questions().get(0).options().get(0).label());
        assertEquals("按 usage tokens 计算", r.questions().get(0).options().get(0).description());
        assertTrue(r.questions().get(1).multiple());
        assertEquals("msg-7", r.messageId());
        assertEquals("call-7", r.callId());

        // 本地待决表已记录；快照为空时仍可见
        List<QuestionRequest> pending = adapter.pendingQuestions(sid);
        assertEquals(1, pending.size());
        assertEquals("que_1", pending.get(0).requestId());
    }

    @Test
    void respond_question_posts_answer_arrays() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-Q1", "oc-question", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        adapter.respondQuestion(session.id(), "que_1",
                List.of(List.of("token 优先"), List.of("60/85 分档", "连续渐变")));
        assertEquals("/question/que_1/reply", postPaths.get(0));
        assertEquals("{\"answers\":[[\"token 优先\"],[\"60/85 分档\",\"连续渐变\"]]}",
                postBodies.get(0));
    }

    @Test
    void reject_question_posts_reject() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-Q1", "oc-question", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        adapter.rejectQuestion(session.id(), "que_1");
        assertEquals("/question/que_1/reject", postPaths.get(0));
    }

    @Test
    void replied_event_clears_pending_and_emits_chunk() throws Exception {
        eventFrames.add(ASKED_JSON);
        eventFrames.add("{\"type\":\"question.replied\",\"properties\":{"
                + "\"sessionID\":\"sess-1\",\"requestID\":\"que_1\","
                + "\"answers\":[[\"token 优先\"],[]]}}");
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-Q1", "oc-question", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();

        CountDownLatch replied = new CountDownLatch(1);
        AtomicReference<SessionStreamChunk.QuestionRepliedChunk> repliedChunk = new AtomicReference<>();
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.QuestionRepliedChunk qr) {
                repliedChunk.set(qr);
                replied.countDown();
            }
        });

        assertTrue(replied.await(5, TimeUnit.SECONDS), "replied chunk never arrived");
        SessionStreamChunk.QuestionRepliedChunk qr = repliedChunk.get();
        assertFalse(qr.rejected());
        assertEquals(2, qr.answers().size());
        assertEquals(List.of("token 优先"), qr.answers().get(0));
        assertTrue(qr.answers().get(1).isEmpty());
        assertTrue(adapter.pendingQuestions(sid).isEmpty(), "replied must clear the pending ask");
    }

    @Test
    void rejected_event_marks_rejected() throws Exception {
        eventFrames.add(ASKED_JSON);
        eventFrames.add("{\"type\":\"question.rejected\",\"properties\":{"
                + "\"sessionID\":\"sess-1\",\"requestID\":\"que_1\"}}");
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-Q1", "oc-question", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        CountDownLatch replied = new CountDownLatch(1);
        AtomicReference<SessionStreamChunk.QuestionRepliedChunk> repliedChunk = new AtomicReference<>();
        adapter.attachListener(session.id(), chunk -> {
            if (chunk instanceof SessionStreamChunk.QuestionRepliedChunk qr) {
                repliedChunk.set(qr);
                replied.countDown();
            }
        });
        assertTrue(replied.await(5, TimeUnit.SECONDS), "rejected chunk never arrived");
        assertTrue(repliedChunk.get().rejected());
    }

    @Test
    void pending_questions_merge_the_serve_snapshot_filtered_by_session() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-Q1", "oc-question", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        pendingSnapshotJson = "["
                + "{\"id\":\"que_1\",\"sessionID\":\"sess-1\",\"questions\":[{"
                + "\"question\":\"q\",\"header\":\"h\",\"options\":[{\"label\":\"a\"}]}]},"
                + "{\"id\":\"que_other\",\"sessionID\":\"sess-9\",\"questions\":[]}"
                + "]";
        List<QuestionRequest> pending = adapter.pendingQuestions(session.id());
        assertEquals(1, pending.size());
        assertEquals("que_1", pending.get(0).requestId());
        assertEquals(1, pending.get(0).questions().size());
    }

    private void health(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void session(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "{\"id\":\"sess-1\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void question(HttpExchange exchange) throws java.io.IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            postPaths.add(exchange.getRequestURI().getPath());
            postBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "true".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } else {
            byte[] body = pendingSnapshotJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private void events(HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        try {
            for (String frame : eventFrames) {
                // Entries are either full SSE frames ({"type":...}) or raw question.asked
                // properties to be wrapped.
                String data = frame.trim().startsWith("{\"type\"")
                        ? frame
                        : "{\"id\":\"evt_q\",\"type\":\"question.asked\",\"properties\":" + frame + "}";
                sse(os, data);
                Thread.sleep(400);
            }
            new CountDownLatch(1).await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // server shutting down
        } finally {
            os.close();
        }
    }

    private static void sse(OutputStream os, String json) throws java.io.IOException {
        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
