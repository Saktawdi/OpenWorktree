package gate.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.adapters.clock.SystemClock;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * serve 进程死亡的自愈链路（T-109 会话僵死回归）：
 *
 * <ol>
 *   <li><b>reader 自愈</b>：serve 死亡后上游 SSE 连续重连失败达到阈值 → healDeadServe 冲刷
 *       在途回合、释放 busy、补发 DoneChunk、清理端口——而不是每 2s 一条 dropped WARN 死循环。</li>
 *   <li><b>回答降级重发</b>：提问挂起期间 serve 死亡、自愈后用户才回答 → 全新 serve 对
 *       /question reply 返回 404，答案不再被静默吞掉，而是折叠成普通消息触发新回合。</li>
 * </ol>
 */
@Tag("slow")
class OpenCodeServeHealTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private JdbcSessionRepository sessions;
    private OpenCodeServeAdapter adapter;
    private final List<String> postPaths = new CopyOnWriteArrayList<>();
    private final List<String> postBodies = new CopyOnWriteArrayList<>();
    private volatile boolean questionReply404;
    private final CountDownLatch promptAccepted = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-heal-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        JdbcAgentConfigRepository agentConfigs = new JdbcAgentConfigRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("oc-heal", "Heal Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test"), now);
        new JdbcTicketRepository(jdbc).insert(new gate.domain.ticket.Ticket("OPEN-H1", "t", "refs/heads/main",
                root.resolve("clone").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));
        Files.createDirectories(root.resolve("clone"));
        port = findFreePort();
        startServer();
        PortAllocator allocator = new PortAllocator(port, port);
        adapter = new OpenCodeServeAdapter(new ProcessRunnerImpl(root.resolve("proc")),
                agentConfigs, sessions, new JdbcTicketRepository(jdbc),
                new JdbcGateTaskRepository(jdbc, new SystemClock()), ticketLocks,
                new SystemClock(), allocator, "", 60,
                gate.adapters.io.AdapterLog.at(Path.of("target", "heal-test-adapters.log")));
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

    /**
     * 真实模拟进程死亡：stop(0) 关闭监听并掐断活动连接，客户端重连拿到的是
     * ConnectException（与真实 serve 进程死亡一致）——而非 HTTP 层可解析的错误响应。
     * "重启后的新实例" = 同端口再起一个 HttpServer。
     */
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
        fakeServer.createContext("/question", this::question);
        fakeServer.createContext("/event", this::events);
        fakeServer.start();
    }

    private static int findFreePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void serve_death_heals_releases_busy_and_emits_done() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-H1", "oc-heal", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();

        // 发一条消息进入 busy（prompt_async 已受理；fake /event 不发 idle，busy 会一直持有）。
        adapter.sendMessage(new AgentSessionPort.SendRequest(sid, "hi", true));
        awaitBusy(sid);

        // 杀掉假 serve：上游 reader 连续 3 次重连失败（约 6s）后必须自愈，而不是无限重试。
        CountDownLatch done = new CountDownLatch(1);
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.DoneChunk) {
                done.countDown();
            }
        });
        killServer();
        assertTrue(done.await(30, TimeUnit.SECONDS),
                "serve 死亡后必须补发 DoneChunk（busy 释放 + UI 解锁的信号）");
        assertFalse(adapter.busySessionIds().contains(sid),
                "serve 死亡必须清空该会话的 busy 计数");
    }

    @Test
    void reply_404_after_heal_degrades_to_plain_message_and_new_turn() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-H1", "oc-heal", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();

        // 1) serve 死亡 → reader 自愈（DoneChunk 是自愈完成的信号，端口映射随之清理）。
        CountDownLatch done = new CountDownLatch(1);
        adapter.attachListener(sid, chunk -> {
            if (chunk instanceof SessionStreamChunk.DoneChunk) {
                done.countDown();
            }
        });
        killServer();
        assertTrue(done.await(30, TimeUnit.SECONDS), "serve death must trigger the heal");

        // 2) "重启后的新 serve 实例"（同端口新 HttpServer）：/question reply 返回 404——
        //    全新进程不持有旧实例的待决提问。
        startServer();
        questionReply404 = true;

        // 3) 此时用户才回答旧提问：不得被静默吞掉——降级为普通消息触发新回合。
        adapter.respondQuestion(sid, "que_lost", List.of(List.of("token 优先")));
        assertTrue(promptAccepted.await(15, TimeUnit.SECONDS),
                "降级回答必须触发新回合（prompt_async）: " + postPaths);
        String promptBody = postBodies.stream().filter(b -> b.contains("\"parts\"")).findFirst().orElse("");
        assertTrue(promptBody.contains("回答已送达") && promptBody.contains("token 优先"),
                "降级消息必须带上用户决策语义: " + promptBody);
        assertTrue(postPaths.stream().anyMatch(p -> p.contains("/question/que_lost/reply")),
                "404 reply must have been attempted before the fallback");
        // busy 由降级触发的普通回合持有：会话恢复运转的直接证据。
        awaitBusy(sid);
        // 待决表已清：卡片不会残留。
        assertTrue(adapter.pendingQuestions(sid).isEmpty(),
                "fallback reply must drop the pending ask");
    }

    @Test
    void reply_404_on_live_serve_stays_resolved_without_fallback() throws Exception {
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "OPEN-H1", "oc-heal", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
        String sid = session.id();

        // serve 一直存活：404 是真正的"已在别处处理"（重复投递），不得降级为新回合。
        questionReply404 = true;
        adapter.respondQuestion(sid, "que_dup", List.of(List.of("token 优先")));
        Thread.sleep(1_000);
        assertFalse(postPaths.stream().anyMatch(p -> p.endsWith("/prompt_async")),
                "live-serve 404 must not fire a new turn");
        assertTrue(sessions.findMessages(sid).stream().noneMatch(m ->
                        m.role() == Role.USER && m.content().contains("回答已送达")),
                "live-serve 404 must not inject a fallback user message");
        assertTrue(adapter.pendingQuestions(sid).isEmpty());
    }

    private void awaitBusy(String sid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !adapter.busySessionIds().contains(sid)) {
            Thread.sleep(50);
        }
        assertTrue(adapter.busySessionIds().contains(sid), "session never became busy: " + postPaths);
    }

    private void health(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void session(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] reqBody = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                ? exchange.getRequestBody().readAllBytes() : new byte[0];
        byte[] body = "{\"id\":\"sess-1\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        if (path.endsWith("/prompt_async")) {
            postPaths.add(path);
            postBodies.add(new String(reqBody, StandardCharsets.UTF_8));
            promptAccepted.countDown();
        }
    }

    private void question(HttpExchange exchange) throws java.io.IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            postPaths.add(exchange.getRequestURI().getPath());
            postBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (questionReply404) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = "true".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } else {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    /** SSE bus: holds the stream open until the server itself is stopped. */
    private void events(HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        try {
            sse(os, "{\"id\":\"evt_h0\",\"type\":\"server.connected\",\"properties\":{}}");
            new CountDownLatch(1).await(20, TimeUnit.SECONDS);
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
