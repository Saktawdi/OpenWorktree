package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import gate.domain.session.SessionMessage;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 后端重启后的会话懒复活：serve 进程随旧后端消亡、sessionPorts 内存映射清空，但 SQLite
 * 会话行仍是 ACTIVE。首次使用（发消息 / 拉模型目录）时按会话行重建 serve、写回新端口、
 * 重接上游事件流；复活失败时 ERROR 必须能送达迟到的 SSE 订阅者（否则 UI 转圈到看门狗超时）。
 */
class OpenCodeServeResurrectTest {

    private Path root;
    private HttpServer fakeServer;
    private int port;
    private DataSource dataSource;
    private JdbcSessionRepository sessions;
    private JdbcAgentConfigRepository agentConfigs;
    private OpenCodeServeAdapter firstRun;
    private OpenCodeServeAdapter restartedRun;
    private final CopyOnWriteArrayList<String> postPaths = new CopyOnWriteArrayList<>();
    private final CountDownLatch promptAccepted = new CountDownLatch(1);
    private volatile boolean failPrompt;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-resurrect-");
        Path db = root.resolve("gate.db");
        dataSource = SqliteDataSourceFactory.create(db);
        SqliteDataSourceFactory.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        agentConfigs = new JdbcAgentConfigRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("oc-resurrect", "Resurrect Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test"), now);
        new JdbcTicketRepository(jdbc).insert(new gate.domain.ticket.Ticket("OPEN-R1", "t", "refs/heads/main",
                root.resolve("clone").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));
        Files.createDirectories(root.resolve("clone"));
        fakeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = fakeServer.getAddress().getPort();
        fakeServer.createContext("/health", this::health);
        fakeServer.createContext("/session", this::session);
        fakeServer.createContext("/event", this::events);
        fakeServer.start();
        // “重启前”的运行实例：建出会话行后即废弃（模拟进程退出，内存映射全部丢失）。
        firstRun = buildAdapter(root.resolve("proc1"));
        // “重启后”的运行实例：共享同一 SQLite 与同一 fake serve 端口。
        restartedRun = buildAdapter(root.resolve("proc2"));
    }

    private OpenCodeServeAdapter buildAdapter(Path procDir) {
        return new OpenCodeServeAdapter(new ProcessRunnerImpl(procDir),
                agentConfigs, sessions, new JdbcTicketRepository(new JdbcTemplate(dataSource)), null,
                new JdbcGateTaskRepository(new JdbcTemplate(dataSource), new SystemClock()), ticketLocks(),
                new SystemClock(), new PortAllocator(port, port), "", 60, null, null, null);
    }

    private FileChannelTicketLockManager ticketLocks() {
        return new FileChannelTicketLockManager(root.resolve("locks"));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (OpenCodeServeAdapter a : List.of(firstRun, restartedRun)) {
            if (a != null) {
                a.close();
            }
        }
        if (fakeServer != null) {
            fakeServer.stop(0);
        }
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    private Session startSessionOnFirstRun() {
        return firstRun.start(new AgentSessionPort.StartRequest(
                "OPEN-R1", "oc-resurrect", root.resolve("clone").toString(), "refs/heads/main",
                "", Map.of()));
    }

    @Test
    void ensure_endpoint_resurrects_stale_session_and_persists_port() {
        Session stale = startSessionOnFirstRun();
        int live = restartedRun.ensureEndpoint(stale.id());
        assertEquals(port, live);
        assertEquals(port, sessions.find(stale.id()).orElseThrow().allocatedPort());
    }

    @Test
    void ensure_endpoint_resurrects_even_a_swept_aborted_session() {
        Session stale = startSessionOnFirstRun();
        // 历史版本启动清扫会把 ACTIVE 全量打成 ABORTED（含 finished_at）；复活不得被终态挡住，
        // 且复活后回到 ACTIVE。
        sessions.update(stale.withStatus(gate.domain.session.SessionStatus.ABORTED)
                .withFinishedAt(Instant.now()));
        int live = restartedRun.ensureEndpoint(stale.id());
        assertEquals(port, live);
        Session row = sessions.find(stale.id()).orElseThrow();
        assertEquals(port, row.allocatedPort());
        assertEquals(gate.domain.session.SessionStatus.ACTIVE, row.status());
    }

    @Test
    void send_after_restart_resurrects_and_delivers_prompt() throws Exception {
        Session stale = startSessionOnFirstRun();
        String taskId = restartedRun.sendMessage(
                new AgentSessionPort.SendRequest(stale.id(), "继续", true));
        assertTrue(promptAccepted.await(10, TimeUnit.SECONDS), "prompt_async never reached the serve");
        // 轮询直到回合被受理落库；期间不允许出现 ERROR 行。
        long deadline = System.currentTimeMillis() + 10_000;
        boolean persisted = false;
        while (System.currentTimeMillis() < deadline) {
            boolean hasError = restartedRun.getHistory(stale.id()).stream()
                    .anyMatch(m -> m.role() == Role.ERROR);
            if (!hasError) {
                persisted = restartedRun.getHistory(stale.id()).stream()
                        .anyMatch(m -> m.role() == Role.USER);
            }
            if (persisted) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(persisted, "user message was never persisted");
        assertTrue(restartedRun.getHistory(stale.id()).stream().noneMatch(m -> m.role() == Role.ERROR),
                "resurrected send must not fail: " + postPaths);
        assertTrue(postPaths.stream().anyMatch(p -> p.endsWith("/prompt_async")), postPaths.toString());
        assertEquals(port, sessions.find(stale.id()).orElseThrow().allocatedPort());
    }

    @Test
    void failed_send_error_reaches_late_sse_subscriber() throws Exception {
        Session stale = startSessionOnFirstRun();
        restartedRun.ensureEndpoint(stale.id());
        failPrompt = true;
        restartedRun.sendMessage(new AgentSessionPort.SendRequest(stale.id(), "继续", true));
        long deadline = System.currentTimeMillis() + 10_000;
        boolean errorRow = false;
        while (System.currentTimeMillis() < deadline) {
            errorRow = restartedRun.getHistory(stale.id()).stream().anyMatch(m -> m.role() == Role.ERROR);
            if (errorRow) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(errorRow, "ERROR row never persisted");
        // ERROR 行落库时浏览器多半才刚挂 SSE：3 秒补偿窗口内必须补发 ErrorChunk。
        CountDownLatch error = new CountDownLatch(1);
        AtomicReference<SessionStreamChunk> chunk = new AtomicReference<>();
        restartedRun.attachListener(stale.id(), c -> {
            if (c instanceof SessionStreamChunk.ErrorChunk ec) {
                chunk.set(ec);
                error.countDown();
            }
        });
        assertTrue(error.await(3, TimeUnit.SECONDS), "late SSE subscriber never received the error");
        assertTrue(restartedRun.busySessionIds().isEmpty()
                || !restartedRun.busySessionIds().contains(stale.id()),
                "failed send must release busy for its own session");
    }

    private void health(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void session(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            // 消费请求体（忽略内容），再按路径分流：create / prompt_async / abort。
            exchange.getRequestBody().readAllBytes();
            if (path.endsWith("/prompt_async")) {
                postPaths.add(path);
                if (failPrompt) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                byte[] ok = "true".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
                exchange.close();
                promptAccepted.countDown();
                return;
            }
            if (path.endsWith("/abort")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"id\":\"sess-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private void events(HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        try {
            new CountDownLatch(1).await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // server shutting down
        } finally {
            os.close();
        }
    }
}
