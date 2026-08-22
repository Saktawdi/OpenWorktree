package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.adapters.clock.SystemClock;
import gate.adapters.io.AdapterLog;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.ProcessRunnerImpl;
import gate.adapters.session.OpenCodeServeAdapter;
import gate.adapters.session.PortAllocator;
import gate.adapters.store.JdbcAgentConfigRepository;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcSessionRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStreamChunk;
import gate.domain.task.GateTaskStatus;
import gate.ports.AgentSessionPort;
import gate.ports.ProviderRepository;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S3 OpenCodeServeAdapter test (执行文档-后端-web §9.2): a fake HTTP server simulates
 * {@code opencode serve} endpoints. The adapter fires {@code prompt_async} and persists the
 * assistant reply from the streamed {@code /event} bus (part updates + completion snapshot).
 */
class OpenCodeServeAdapterTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private JdbcAgentConfigRepository agentConfigs;
    private JdbcSessionRepository sessions;
    private JdbcTicketRepository ticketRepository;
    private JdbcGateTaskRepository tasks;
    private FileChannelTicketLockManager ticketLocks;
    private OpenCodeServeAdapter adapter;
    private String lastMessageRequest;
    private CountDownLatch eventStreamHeld;
    // When true the fake /event frame pushes a partial assistant turn (one text part +
    // completion, NO idle) so the test can exercise the abort/supersede recovery flush.
    private boolean partialTurnOnly;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-test-");
        partialTurnOnly = false;
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now),
                now);
        agentConfigs = new JdbcAgentConfigRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        ticketRepository = new JdbcTicketRepository(jdbc);
        tasks = new JdbcGateTaskRepository(jdbc, new SystemClock());
        ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));

        agentConfigs.insert(new AgentConfig("opencode-test", "OpenCode Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test", false), now);
        ticketRepository.insert(new gate.domain.ticket.Ticket("OPEN-1", "t", "refs/heads/main",
                root.resolve("clone").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));
        Path clone = root.resolve("clone");
        Files.createDirectories(clone);

        fakeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The blocking SSE /event handler must not starve other requests.
        fakeServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = fakeServer.getAddress().getPort();
        fakeServer.createContext("/health", this::health);
        fakeServer.createContext("/session", this::session);
        fakeServer.createContext("/session/sess-1/prompt_async", this::promptAsync);
        fakeServer.createContext("/event", this::events);
        fakeServer.start();

        PortAllocator allocator = new PortAllocator(port, port);
        adapter = new OpenCodeServeAdapter(new ProcessRunnerImpl(root.resolve("proc")),
                agentConfigs, sessions, ticketRepository, tasks, ticketLocks, new SystemClock(),
                allocator, "", 60, AdapterLog.at(root.resolve("adapters.log")));
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

    @Test
    void aborted_turn_persists_partial_reply_degraded() throws Exception {
        partialTurnOnly = true;
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-1", "opencode-test", root.resolve("clone").toString(), "refs/heads/main",
                "hello", Map.of()));
        // Wait until the half-streamed text has actually been buffered before aborting, so the
        // test does not race the upstream reader.
        CountDownLatch sawPartial = new CountDownLatch(1);
        adapter.attachListener(session.id(), chunk -> {
            if (chunk instanceof SessionStreamChunk.ContentChunk c
                    && c.textDelta().contains("partial reply in progress")) {
                sawPartial.countDown();
            }
        });
        assertTrue(sawPartial.await(10, TimeUnit.SECONDS), "partial text never streamed");

        adapter.abort(session.id());

        // The interrupted turn survives the abort as a degraded assistant reply, so switching
        // back to this session (history reload) still shows the half-streamed content.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        SessionMessage partial = null;
        while (System.nanoTime() < deadline && partial == null) {
            for (SessionMessage m : sessions.findMessages(session.id())) {
                if (m.role() == Role.ASSISTANT && m.degraded()) {
                    partial = m;
                }
            }
            if (partial == null) {
                Thread.sleep(50);
            }
        }
        assertNotNull(partial, "interrupted turn content was lost on abort");
        assertTrue(partial.content().contains("partial reply in progress"), partial.content());
    }

    @Test
    void start_and_prompt_async_streams_reply_via_event_bus() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-1", "opencode-test", root.resolve("clone").toString(), "refs/heads/main",
                "hello", Map.of()));
        assertNotNull(session.id());
        assertEquals("sess-1", session.cliSessionId());
        assertEquals(port, session.allocatedPort());

        String taskId = adapter.sendMessage(new AgentSessionPort.SendRequest(session.id(), "hi", true));
        waitForTask(taskId);

        SessionMessage assistant = waitForAssistant(session.id());
        assertEquals("hello opencode", assistant.content());
        // A finished step's text re-announced under a fresh part id must not double the body.
        assertEquals(38726L, assistant.usage().promptTokens());
        assertEquals(89L, assistant.usage().completionTokens());
        assertEquals(38866L, assistant.usage().totalTokens());

        // Tool-call state streamed on the bus must survive persistence so session switches
        // re-render 工具调用 instead of degrading to plain text.
        assertEquals(1, assistant.toolCalls().size());
        gate.domain.session.ToolCall call = assistant.toolCalls().get(0);
        assertEquals("bash", call.name());
        assertEquals("{\"command\":\"git log\"}", call.argumentsJson());
        assertEquals("commit log output", call.resultJson());

        SessionMessage second = waitForAssistantContent(session.id(), "second turn reply");
        assertEquals("second turn reply", second.content());

        assertNotNull(lastMessageRequest);
        assertTrue(lastMessageRequest.contains("\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}]"),
                lastMessageRequest);
        assertTrue(lastMessageRequest.contains(
                "\"model\":{\"providerID\":\"opencode\",\"modelID\":\"test-model\"}"),
                lastMessageRequest);
    }

    private SessionMessage waitForAssistant(String sessionId) throws Exception {
        return waitForAssistantContent(sessionId, "hello opencode");
    }

    private SessionMessage waitForAssistantContent(String sessionId, String content) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<SessionMessage> history = sessions.findMessages(sessionId);
            SessionMessage found = history.stream()
                    .filter(m -> m.role() == Role.ASSISTANT && content.equals(m.content()))
                    .findFirst().orElse(null);
            if (found != null) {
                return found;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("assistant reply never arrived: " + sessions.findMessages(sessionId));
    }

    private void waitForTask(String taskId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (tasks.find(taskId).map(t -> t.status() == GateTaskStatus.SUCCEEDED).orElse(false)) {
                return;
            }
            if (tasks.find(taskId).map(t -> t.status() == GateTaskStatus.FAILED).orElse(false)) {
                throw new AssertionError("task failed: " + tasks.find(taskId).orElseThrow().errorJson());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for task " + taskId);
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

    private void promptAsync(HttpExchange exchange) throws java.io.IOException {
        lastMessageRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Simulates opencode's /event SSE bus for one turn, then holds the stream open. */
    private void events(HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        try {
            if (partialTurnOnly) {
                sse(os, "{\"id\":\"evt_a0\",\"type\":\"server.connected\",\"properties\":{}}");
                // Assistant role announced, but the turn is only half-finished: one text part
                // and a step completion, NO idle — exercises the recovery flush path.
                sse(os, "{\"id\":\"evt_a1\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_partial\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                        + "\"time\":{\"created\":1}}}}");
                sse(os, "{\"id\":\"evt_a2\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                        + "{\"id\":\"prt_p1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_partial\","
                        + "\"type\":\"text\",\"text\":\"partial reply in progress\"}}}");
                // Deliberately NO message.updated(completed) and NO session.status=idle: the
                // turn is cut mid-step, so recovery must drain the per-message buffers.
                // Hold the stream open so the adapter does not churn on reconnects mid-test.
                eventStreamHeld = new CountDownLatch(1);
                eventStreamHeld.await(15, TimeUnit.SECONDS);
                return;
            }
            sse(os, "{\"id\":\"evt_1\",\"type\":\"server.connected\",\"properties\":{}}");
            // User echo: role announced first, then its text part — must be skipped entirely.
            sse(os, "{\"id\":\"evt_1b\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                    + "{\"id\":\"msg_u\",\"sessionID\":\"sess-1\",\"role\":\"user\","
                    + "\"time\":{\"created\":0}}}}");
            sse(os, "{\"id\":\"evt_1c\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_u\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_u\","
                    + "\"type\":\"text\",\"text\":\"hi\"}}}");
            sse(os, "{\"id\":\"evt_1d\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                    + "{\"id\":\"msg_1\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                    + "\"time\":{\"created\":1}}}}");
            sse(os, "{\"id\":\"evt_2\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_1\","
                    + "\"type\":\"text\",\"text\":\"hello \"}}}");
            sse(os, "{\"id\":\"evt_3\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_1\","
                    + "\"type\":\"text\",\"text\":\"hello opencode\"},\"delta\":\"opencode\"}}");
            // Tool call lifecycle: running with input, then completed with output.
            sse(os, "{\"id\":\"evt_3b\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_t1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_1\","
                    + "\"type\":\"tool\",\"callID\":\"call_1\",\"tool\":\"bash\","
                    + "\"state\":{\"status\":\"running\",\"input\":{\"command\":\"git log\"}}}}}");
            sse(os, "{\"id\":\"evt_3c\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_t1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_1\","
                    + "\"type\":\"tool\",\"callID\":\"call_1\",\"tool\":\"bash\","
                    + "\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"git log\"},"
                    + "\"output\":\"commit log output\"}}}}");
            // Reconciliation echo: the finished step's text re-sent under a fresh part id.
            sse(os, "{\"id\":\"evt_3d\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_1dup\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_1\","
                    + "\"type\":\"text\",\"text\":\"hello opencode\"}}}");
            sse(os, "{\"id\":\"evt_4\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_2\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_1\","
                    + "\"type\":\"step-finish\",\"reason\":\"stop\",\"cost\":0,"
                    + "\"tokens\":{\"input\":38726,\"output\":89,\"reasoning\":0,"
                    + "\"cache\":{\"read\":51,\"write\":0}}}}}");
            sse(os, "{\"id\":\"evt_5\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                    + "{\"id\":\"msg_1\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                    + "\"time\":{\"created\":1,\"completed\":2},"
                    + "\"tokens\":{\"input\":38726,\"output\":89,\"reasoning\":0,"
                    + "\"cache\":{\"read\":51,\"write\":0}}}}}");
            sse(os, "{\"id\":\"evt_6\",\"type\":\"session.status\",\"properties\":"
                    + "{\"sessionID\":\"sess-1\",\"status\":{\"type\":\"idle\"}}}");
            // Second assistant message whose text part races ahead of its role announcement:
            // the snapshot must still be buffered and persisted (no empty bubble after reload).
            sse(os, "{\"id\":\"evt_7\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                    + "{\"id\":\"prt_3\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_2\","
                    + "\"type\":\"text\",\"text\":\"second turn reply\"}}}");
            sse(os, "{\"id\":\"evt_8\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                    + "{\"id\":\"msg_2\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                    + "\"time\":{\"created\":3,\"completed\":4},"
                    + "\"tokens\":{\"input\":10,\"output\":5,\"reasoning\":0}}}}");
            // Turn boundary: the reply flushes to history when the session goes idle.
            sse(os, "{\"id\":\"evt_9\",\"type\":\"session.status\",\"properties\":"
                    + "{\"sessionID\":\"sess-1\",\"status\":{\"type\":\"idle\"}}}");
            // Hold the stream open so the adapter does not churn on reconnects mid-test.
            eventStreamHeld = new CountDownLatch(1);
            eventStreamHeld.await(15, TimeUnit.SECONDS);
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
