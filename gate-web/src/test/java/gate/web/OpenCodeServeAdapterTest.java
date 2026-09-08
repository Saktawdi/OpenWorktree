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
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S3 OpenCodeServeAdapter test (执行文档-后端-web §9.2): a fake HTTP server simulates
 * {@code opencode serve} endpoints. The adapter fires {@code prompt_async} and persists the
 * assistant reply from the streamed {@code /event} bus (part updates + completion snapshot).
 */
@Tag("slow")
class OpenCodeServeAdapterTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private JdbcTemplate jdbc;
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
    // busy 回归测试专用：SSE 只推半个 text part + step-finish 快照（不发 idle）；主线程
    // 放行 deferredIdleGate 后再补发 idle。模拟"accepted 但仍在思考中"与"排队第二回合"两种窗口。
    private boolean holdResponseParts;
    private CountDownLatch deferredIdleGate;
    private boolean rejectPrompt;
    // T-107 渲染修复：回合中段 steer 注入回归。fake 在首个 step 完成后扣住事件流；
    // 主线程放行 steerEchoGate 后补发 user 公告（插队回声）→ 续写 → idle。
    private boolean steerMidTurn;
    private CountDownLatch steerEchoGate;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-test-");
        partialTurnOnly = false;
        holdResponseParts = false;
        rejectPrompt = false;
        steerMidTurn = false;
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        jdbc = new JdbcTemplate(ds);
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

    @Test
    void busy_counts_session_from_enqueue_until_turn_end_idle() throws Exception {
        holdResponseParts = true;
        deferredIdleGate = new CountDownLatch(1);
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-1", "opencode-test", root.resolve("clone").toString(), "refs/heads/main",
                "hello", Map.of()));
        // 空闲会话 + 连接快照 idle 均不计 busy（快照无受理记录，必须被忽略）。
        Thread.sleep(300);
        assertTrue(adapter.busySessionIds().isEmpty());

        adapter.sendMessage(new AgentSessionPort.SendRequest(session.id(), "hi", true));
        awaitBusy(adapter, session.id());
        // 核心回归：prompt_async 已受理、回合仍在思考（idle 被扣住）——busy 必须保持。
        // 旧实现（runSend finally 释放）会在受理瞬间清零：实际运行一个智能体，统计却返回 0。
        Thread.sleep(800);
        assertTrue(adapter.busySessionIds().contains(session.id()),
                "busy must survive prompt acceptance until the turn truly ends");

        // 回合终点到达 → 释放；重复 idle 不得破坏状态。
        deferredIdleGate.countDown();
        awaitBusyGone(adapter, session.id());
        Thread.sleep(300);
        assertTrue(adapter.busySessionIds().isEmpty());
    }

    @Test
    void busy_released_when_prompt_async_is_rejected() throws Exception {
        rejectPrompt = true;
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-1", "opencode-test", root.resolve("clone").toString(), "refs/heads/main",
                "hello", Map.of()));
        String taskId = adapter.sendMessage(new AgentSessionPort.SendRequest(session.id(), "hi", true));
        awaitBusy(adapter, session.id());
        // prompt_async 被拒：该回合永远不会有 idle，任务失败且 busy 兜底释放。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (tasks.find(taskId).map(t -> t.status() == GateTaskStatus.FAILED).orElse(false)) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(GateTaskStatus.FAILED, tasks.find(taskId).orElseThrow().status());
        awaitBusyGone(adapter, session.id());
    }

    @Test
    void steer_failing_before_riding_check_preserves_running_turn_busy() throws Exception {
        // 在跑回合（busy 计数由它持有）：prompt_async 已受理、idle 被扣住。
        holdResponseParts = true;
        deferredIdleGate = new CountDownLatch(1);
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-1", "opencode-test", root.resolve("clone").toString(), "refs/heads/main",
                "hello", Map.of()));
        adapter.sendMessage(new AgentSessionPort.SendRequest(session.id(), "hi", true));
        awaitBusy(adapter, session.id());
        Thread.sleep(800);
        assertTrue(adapter.busySessionIds().contains(session.id()));

        // steer 在 riding 判定前抛错的窗口：抹掉会话行的 cli_session_id（模拟续接信息缺失），
        // steer 的 runSend 在 "session has no opencode endpoint" 处抛 GateException——
        // 此刻 riding 仍为 false 且 steer 从未计数，旧代码据此误走 else 分支强清 busy。
        jdbc.update("UPDATE agent_session SET cli_session_id = NULL WHERE id = ?", session.id());
        List<SessionStreamChunk> seen = new ArrayList<>();
        adapter.attachListener(session.id(), seen::add);
        String steerTask = adapter.sendMessage(new AgentSessionPort.SendRequest(
                session.id(), "steer me", true, List.of(), "steer"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (tasks.find(steerTask).map(t -> t.status() == GateTaskStatus.FAILED).orElse(false)) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(GateTaskStatus.FAILED, tasks.find(steerTask).orElseThrow().status());
        // 核心回归：steer 未计数、也未确认骑乘前失败——不得释放/误清在跑回合的 busy。
        Thread.sleep(300);
        assertTrue(adapter.busySessionIds().contains(session.id()),
                "steer 在 riding 判定前抛错，绝不能误清在跑回合的 busy 计数");
        // 失败只落 ERROR 行；正在流的回合视图不能被 ErrorChunk 掐断。
        assertTrue(seen.stream().noneMatch(c -> c instanceof SessionStreamChunk.ErrorChunk),
                "riding 判定前的 steer 失败不得向在跑回合补发 ErrorChunk");
        assertTrue(sessions.findMessages(session.id()).stream()
                .anyMatch(m -> m.role() == Role.ERROR), "steer 失败必须以 ERROR 行落库");

        // 原回合的终点照常消耗它自己的那一次计数：放行后 busy 干净清零、不残留。
        deferredIdleGate.countDown();
        awaitBusyGone(adapter, session.id());
        Thread.sleep(300);
        assertTrue(adapter.busySessionIds().isEmpty());
    }

    @Test
    void steer_midturn_injects_timeline_segment_and_persists_row_id() throws Exception {
        steerMidTurn = true;
        steerEchoGate = new CountDownLatch(1);
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-1", "opencode-test", root.resolve("clone").toString(), "refs/heads/main",
                "hello", Map.of()));
        // 回合 1 正常受理（busy 归它）；steer 骑乘其上。
        String normalTask = adapter.sendMessage(new AgentSessionPort.SendRequest(session.id(), "hi", true));
        waitForTask(normalTask);

        // reader 线程并发追加（emitChunk 走 listener）：必须用并发集合，流式断言才不会被
        // ConcurrentModificationException 打断。
        List<SessionStreamChunk> chunks = new java.util.concurrent.CopyOnWriteArrayList<>();
        adapter.attachListener(session.id(), chunks::add);

        // steer1（client-1）：文本含图片引用行（与 POST /messages 的 outgoing 同构——
        // steer_injected 帧必须携带该权威文本，前端据此在流式期间还原缩略图）。
        // steer2（client-2）：同样入队但上游从不公告它——重复的 user 公告帧（evt_s4b）
        // 绝不能把它误弹出注入。
        String steerText = "插队：先看测试输出\n[图片引用 #1] .gate/chat-images/shot.png";
        String steerTask = adapter.sendMessage(new AgentSessionPort.SendRequest(
                session.id(), steerText, true, List.of(), "steer", "client-1"));
        waitForTask(steerTask);
        String secondSteerTask = adapter.sendMessage(new AgentSessionPort.SendRequest(
                session.id(), "第二条插队（上游永不公告）", true, List.of(), "steer", "client-2"));
        waitForTask(secondSteerTask);
        // 任务成功 ⇒ POST 已受理 ⇒ pendingSteers 已登记（先于 POST 登记），放行插队回声。
        steerEchoGate.countDown();

        // steer_injected 对账帧：messageId = client_message_id（乐观气泡锚点），文本随行。
        // 只此一帧——重复的 user 公告不得把 client-2 也弹出注入（FIFO 串位）。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<SessionStreamChunk.SteerInjectedChunk> injectedAll = new ArrayList<>();
        while (System.nanoTime() < deadline && injectedAll.isEmpty()) {
            injectedAll = chunks.stream()
                    .filter(SessionStreamChunk.SteerInjectedChunk.class::isInstance)
                    .map(SessionStreamChunk.SteerInjectedChunk.class::cast)
                    .toList();
            if (injectedAll.isEmpty()) {
                Thread.sleep(50);
            }
        }
        // 帧可能逐个到达：再等一拍让潜在的重复帧（若实现有误）也到齐。
        Thread.sleep(500);
        injectedAll = chunks.stream()
                .filter(SessionStreamChunk.SteerInjectedChunk.class::isInstance)
                .map(SessionStreamChunk.SteerInjectedChunk.class::cast)
                .toList();
        assertEquals(1, injectedAll.size(),
                "重复 user 公告不得触发第二次注入: " + injectedAll);
        SessionStreamChunk.SteerInjectedChunk injected = injectedAll.get(0);
        assertNotNull(injected, "steer_injected 帧未到达");
        assertEquals(session.id(), injected.sessionId());
        assertEquals("client-1", injected.messageId());
        assertTrue(injected.text().contains("插队：先看测试输出"), injected.text());
        // 权威文本含图片引用行：前端流式期间按其还原插队图片缩略图（不再等刷新）。
        assertTrue(injected.text().contains("[图片引用 #1] .gate/chat-images/shot.png"),
                injected.text());

        // 回合终点落库后：USER 行以 client_message_id 落库；ASSISTANT 行的时间线
        // 为 text("before") → steer(client-1) → text("after")，顺序即注入位置。
        gate.domain.session.SessionMessage assistant = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && assistant == null) {
            for (gate.domain.session.SessionMessage m : sessions.findMessages(session.id())) {
                if (m.role() == Role.ASSISTANT
                        && m.parts().stream().anyMatch(p -> p.isSteer())) {
                    assistant = m;
                }
            }
            if (assistant == null) {
                Thread.sleep(50);
            }
        }
        assertNotNull(assistant, "含 steer 段的回合未被落库");
        assertEquals(3, assistant.parts().size(), assistant.parts().toString());
        assertTrue(assistant.parts().get(0).isText()
                && assistant.parts().get(0).text().contains("before"), assistant.parts().toString());
        gate.domain.session.TurnPart steerPart = assistant.parts().get(1);
        assertTrue(steerPart.isSteer(), assistant.parts().toString());
        assertEquals("client-1", steerPart.name(), "steer 段必须携带 USER 行 id（对账锚点）");
        assertTrue(steerPart.text().contains("插队：先看测试输出"), steerPart.text());
        // 权威文本（含引用行）随段落库：历史重载按其还原缩略图，与实时帧同口径。
        assertTrue(steerPart.text().contains("[图片引用 #1] .gate/chat-images/shot.png"),
                steerPart.text());
        assertTrue(assistant.parts().get(2).isText()
                && assistant.parts().get(2).text().contains("after"), assistant.parts().toString());
        // parts_blob 往返（writeParts/parseParts）保真：类型与 name 不被降级成 text。
        assertTrue(sessions.findMessages(session.id()).stream()
                .anyMatch(m -> "client-1".equals(m.id()) && m.role() == Role.USER),
                "USER 行必须以 client_message_id 落库");
        // 插队回声的 user part 不得漏进正文流（无 ContentChunk 携带其文本）。
        assertTrue(chunks.stream()
                        .filter(SessionStreamChunk.ContentChunk.class::isInstance)
                        .map(SessionStreamChunk.ContentChunk.class::cast)
                        .noneMatch(c -> c.textDelta().contains("插队：先看测试输出")),
                "user echo part 泄漏进了正文流");
    }

    private void awaitBusy(OpenCodeServeAdapter adapter, String sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (adapter.busySessionIds().contains(sessionId)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("busy never registered for " + sessionId);
    }

    private void awaitBusyGone(OpenCodeServeAdapter adapter, String sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!adapter.busySessionIds().contains(sessionId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("busy never cleared for " + sessionId);
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
        if (rejectPrompt) {
            byte[] err = "{\"error\":\"rejected\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, err.length);
            exchange.getResponseBody().write(err);
            exchange.close();
            return;
        }
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
            if (holdResponseParts) {
                // busy 回归：回合内容完整推送但 idle 被扣住，模拟“prompt_async 已受理、
                // 模型仍在思考”的长窗口；主线程放行 deferredIdleGate 后补发两个 idle
                // （第二个用于验证重复 send 的引用计数逐次递减）。
                // 连接建立时先补一帧快照 idle（opencode 重连/初次连接的 status 快照语义）：
                // 无受理记录，必须被忽略，不得影响后续任何计数。
                sse(os, "{\"id\":\"evt_b0s\",\"type\":\"session.status\",\"properties\":"
                        + "{\"sessionID\":\"sess-1\",\"status\":{\"type\":\"idle\"}}}");
                sse(os, "{\"id\":\"evt_b0\",\"type\":\"server.connected\",\"properties\":{}}");
                sse(os, "{\"id\":\"evt_b1\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_b1\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                        + "\"time\":{\"created\":1}}}}");
                sse(os, "{\"id\":\"evt_b2\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                        + "{\"id\":\"prt_b1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_b1\","
                        + "\"type\":\"text\",\"text\":\"hello \"}}}");
                sse(os, "{\"id\":\"evt_b3\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                        + "{\"id\":\"prt_b2\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_b1\","
                        + "\"type\":\"step-finish\",\"reason\":\"stop\",\"cost\":0,"
                        + "\"tokens\":{\"input\":10,\"output\":5,\"reasoning\":0}}}}");
                deferredIdleGate.await(15, TimeUnit.SECONDS);
                sse(os, "{\"id\":\"evt_b4\",\"type\":\"session.status\",\"properties\":"
                        + "{\"sessionID\":\"sess-1\",\"status\":{\"type\":\"idle\"}}}");
                sse(os, "{\"id\":\"evt_b5\",\"type\":\"session.status\",\"properties\":"
                        + "{\"sessionID\":\"sess-1\",\"status\":{\"type\":\"idle\"}}}");
                eventStreamHeld = new CountDownLatch(1);
                eventStreamHeld.await(15, TimeUnit.SECONDS);
                return;
            }
            if (steerMidTurn) {
                // T-107 渲染修复：连接后先扣住事件流；主线程确认骑乘 steer 已受理
                // （任务 SUCCEEDED ⇒ pendingSteers 已登记、前置普通 send 的重置已完成）
                // 再推整个回合——assistant 活动先行，中段的 user 公告即插队回声：
                // reader 注入 steer 段 + steer_injected 对账帧，idle 落库 text→steer→text。
                sse(os, "{\"id\":\"evt_s0\",\"type\":\"server.connected\",\"properties\":{}}");
                steerEchoGate.await(15, TimeUnit.SECONDS);
                sse(os, "{\"id\":\"evt_s1\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_s1\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                        + "\"time\":{\"created\":1}}}}");
                sse(os, "{\"id\":\"evt_s2\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                        + "{\"id\":\"prt_s1\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_s1\","
                        + "\"type\":\"text\",\"text\":\"before \"}}}");
                sse(os, "{\"id\":\"evt_s3\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_s1\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                        + "\"time\":{\"created\":1,\"completed\":2},"
                        + "\"tokens\":{\"input\":10,\"output\":5,\"reasoning\":0}}}}");
                // 插队回声：user 公告 + 原文 part（part 必须被彻底跳过，不得漏进正文流）。
                // 文本含图片引用行（与 POST /messages 的 outgoing 同构，前端据此还原缩略图）。
                sse(os, "{\"id\":\"evt_s4\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_s9\",\"sessionID\":\"sess-1\",\"role\":\"user\","
                        + "\"time\":{\"created\":3}}}}");
                sse(os, "{\"id\":\"evt_s5\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                        + "{\"id\":\"prt_s9\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_s9\","
                        + "\"type\":\"text\",\"text\":\"插队：先看测试输出\\n[图片引用 #1] .gate/chat-images/shot.png\"}}}");
                // 同一 user 消息的重复 message.updated（完成帧）：不得再次触发注入——
                // 否则 FIFO 会弹出队列里下一条未公告的待注入条目（多条插队串位）。
                sse(os, "{\"id\":\"evt_s4b\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_s9\",\"sessionID\":\"sess-1\",\"role\":\"user\","
                        + "\"time\":{\"created\":3,\"completed\":4}}}}");
                sse(os, "{\"id\":\"evt_s6\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_s2\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                        + "\"time\":{\"created\":4}}}}");
                sse(os, "{\"id\":\"evt_s7\",\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                        + "{\"id\":\"prt_s2\",\"sessionID\":\"sess-1\",\"messageID\":\"msg_s2\","
                        + "\"type\":\"text\",\"text\":\"after\"}}}");
                sse(os, "{\"id\":\"evt_s8\",\"type\":\"message.updated\",\"properties\":{\"info\":"
                        + "{\"id\":\"msg_s2\",\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                        + "\"time\":{\"created\":4,\"completed\":5},"
                        + "\"tokens\":{\"input\":20,\"output\":8,\"reasoning\":0}}}}");
                sse(os, "{\"id\":\"evt_s9\",\"type\":\"session.status\",\"properties\":"
                        + "{\"sessionID\":\"sess-1\",\"status\":{\"type\":\"idle\"}}}");
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
