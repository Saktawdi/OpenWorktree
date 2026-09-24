package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.adapters.clock.SystemClock;
import gate.adapters.io.AdapterLog;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.ProcessRunnerImpl;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 上游事件流可靠性（多会话方案 P1-4 / P2-7 的验收，doc/local/plan/multi-session-reliability.md）：
 *
 * <ol>
 *   <li><b>P1-4 断线对账</b>：opencode /event 不回放历史（Last-Event-ID 是无效游标），
 *       断线窗口内静默结束的回合其 idle 终点帧永久丢失——reader 重连成功后必须按上游
 *       存储对账：回填缺失回合、补发 DoneChunk、释放 busy，不再依赖「再发一句继续」。</li>
 *   <li><b>P2-7 停滞看门狗</b>：长时间无事件 ≠ serve 死亡。serve 存活只强制重连
 *       （不 kill、不清端口、不释放 busy）；serve 确实不可达才直接自愈——不等 reader
 *       攒满重连失败阈值。</li>
 * </ol>
 */
@Tag("slow")
class OpenCodeServeUpstreamReliabilityTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private JdbcSessionRepository sessions;
    private OpenCodeServeAdapter adapter;
    private final CountDownLatch promptAccepted = new CountDownLatch(1);

    /** 断线窗口后上游 /session/{id}/message 应返回的完整消息列表（null = 不带对账信号）。 */
    private volatile String messagePayload;
    private final AtomicInteger eventConnections = new AtomicInteger();
    private final CountDownLatch secondEventConnection = new CountDownLatch(1);
    private final List<String> receivedChunks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-reliability-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        JdbcAgentConfigRepository agentConfigs = new JdbcAgentConfigRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("oc-rel", "Reliability Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test"), now);
        new JdbcTicketRepository(jdbc).insert(new gate.domain.ticket.Ticket("OPEN-R1", "t",
                "refs/heads/main", root.resolve("clone").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));
        Files.createDirectories(root.resolve("clone"));
        port = findFreePort();
        startServer();
        PortAllocator allocator = new PortAllocator(port, port);
        adapter = new OpenCodeServeAdapter(new ProcessRunnerImpl(root.resolve("proc")),
                agentConfigs, sessions, new JdbcTicketRepository(jdbc),
                new JdbcGateTaskRepository(jdbc, new SystemClock()), ticketLocks,
                new SystemClock(), allocator, "", 60,
                gate.adapters.io.AdapterLog.at(Path.of("target", "reliability-test-adapters.log")));
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
    void turn_completing_during_disconnect_is_reconciled_on_reconnect() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-R1", "oc-rel", root.resolve("clone").toString(), "refs/heads/main", "", Map.of()));
        String sid = session.id();
        adapter.sendMessage(new AgentSessionPort.SendRequest(sid, "hi", true));
        awaitBusy(sid);

        // 断线窗口内"上游"完成了一个回合（重启后的 /message 返回已完结 assistant 消息），
        // 但 /event 永远不会再送来 idle——终点帧随断线丢失，busy 只能靠重连对账收口。
        messagePayload = messageList("断线窗口内完成的回复", System.currentTimeMillis() + 5_000);
        CountDownLatch done = new CountDownLatch(1);
        adapter.attachListener(sid, chunk -> {
            receivedChunks.add(chunk.getClass().getSimpleName());
            if (chunk instanceof SessionStreamChunk.DoneChunk) {
                done.countDown();
            }
        });
        killServer();
        startServer();

        assertTrue(done.await(30, TimeUnit.SECONDS),
                "重连对账必须补发 DoneChunk（否则 UI 转圈到用户手动发继续）: " + receivedChunks);
        awaitNotBusy(sid);
        assertFalse(adapter.busySessionIds().contains(sid), "对账收口后 busy 必须释放");
        assertTrue(sessions.findMessages(sid).stream().anyMatch(m ->
                        m.role() == Role.ASSISTANT && m.content().contains("断线窗口内完成的回复")),
                "断线窗口内完成的回合必须按上游存储回填落库");
    }

    @Test
    void stalled_upstream_with_live_serve_reconnects_without_kill() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-R1", "oc-rel", root.resolve("clone").toString(), "refs/heads/main", "", Map.of()));
        String sid = session.id();
        adapter.sendMessage(new AgentSessionPort.SendRequest(sid, "hi", true));
        awaitBusy(sid);

        // 故障注入：/event 静默挂流（连接后不再发任何帧），停滞阈值调短到 300ms。
        adapter.stallTimeoutMs = 300;
        Thread.sleep(600);
        CountDownLatch done = new CountDownLatch(1);
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.DoneChunk) {
                done.countDown();
            }
        });
        adapter.checkStalledUpstreams();

        // serve 存活：只允许强制重连（/event 第二次连接），绝不 kill/清端口/释放 busy。
        assertTrue(secondEventConnection.await(15, TimeUnit.SECONDS),
                "停滞 sweep 须强制 reader 重连（/event 收到第二条连接）");
        Thread.sleep(1_000);
        assertFalse(done.await(0, TimeUnit.SECONDS),
                "serve 存活时不得触发自愈补发 DoneChunk: " + receivedChunks);
        assertTrue(adapter.busySessionIds().contains(sid),
                "serve 存活时不得释放 busy（回合还在跑）");
    }

    @Test
    void stalled_upstream_with_dead_serve_heals_without_reconnect_threshold() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-R1", "oc-rel", root.resolve("clone").toString(), "refs/heads/main", "", Map.of()));
        String sid = session.id();
        adapter.sendMessage(new AgentSessionPort.SendRequest(sid, "hi", true));
        awaitBusy(sid);

        adapter.stallTimeoutMs = 300;
        Thread.sleep(600);
        CountDownLatch done = new CountDownLatch(1);
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.DoneChunk) {
                done.countDown();
            }
        });
        killServer();
        // sweep 直接探活失败 → 自愈立即发生，不等 reader 攒满 3 次重连失败（约 6s+）。
        adapter.checkStalledUpstreams();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "停滞 sweep 须直达自愈（补发 DoneChunk）");
        assertFalse(adapter.busySessionIds().contains(sid), "自愈后 busy 必须释放");
    }

    private void awaitBusy(String sid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !adapter.busySessionIds().contains(sid)) {
            Thread.sleep(50);
        }
        assertTrue(adapter.busySessionIds().contains(sid), "session never became busy");
    }

    /**
     * 终点收口的两行代码（emit DoneChunk → releaseBusyOnTurnEnd）在 reader 线程上相邻执行，
     * listener 被 done 放行可能抢在释放前一拍——轮询等一拍再断言（与自然 idle 路径同款语义）。
     */
    private void awaitNotBusy(String sid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && adapter.busySessionIds().contains(sid)) {
            Thread.sleep(50);
        }
    }

    private void killServer() {
        if (fakeServer != null) {
            fakeServer.stop(0);
            fakeServer = null;
        }
    }

    private void startServer() throws java.io.IOException {
        fakeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        fakeServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        fakeServer.createContext("/health", this::health);
        fakeServer.createContext("/session", this::session);
        fakeServer.createContext("/event", this::events);
        fakeServer.start();
    }

    private static int findFreePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void health(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void session(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/message")) {
            String body = messagePayload != null ? messagePayload : "{\"none\":true}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
            return;
        }
        byte[] body = "{\"id\":\"sess-1\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        if (path.endsWith("/prompt_async")) {
            promptAccepted.countDown();
        }
    }

    /** SSE bus：连接后保持静默（停滞注入的关键——lastEventAt 停在连接时刻）。 */
    private void events(HttpExchange exchange) throws java.io.IOException {
        eventConnections.incrementAndGet();
        if (eventConnections.get() >= 2) {
            secondEventConnection.countDown();
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        try {
            new CountDownLatch(1).await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // server shutting down
        } finally {
            os.close();
        }
    }

    /** 上游 /message 的[{info, parts}]载荷：一条已完结的 assistant 消息（sessionID 须对上）。 */
    private static String messageList(String text, long createdMs) {
        return "[{\"info\":{\"sessionID\":\"sess-1\",\"role\":\"assistant\","
                + "\"time\":{\"created\":" + createdMs + ",\"completed\":" + (createdMs + 1_000) + "},"
                + "\"providerID\":\"manual\",\"modelID\":\"test-model\"},"
                + "\"parts\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]";
    }
}
