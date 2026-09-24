package gate.web;

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
import gate.domain.session.Session;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
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
 * 多会话派发并发性验收（P1-3）：全局单线程派发器的时代，一个会话的 runSend 慢
 * （此处用假 serve 的 prompt_async 延迟应答模拟）会把其余会话的消息发送全部堵在
 * 队列里串行排队。按会话派发后，不同会话必须并行——三个会话同时发消息，
 * prompt_async 的到达时刻必须有至少两笔重叠。
 */
@Tag("slow")
class OpenCodeServeDispatchConcurrencyTest {

    private static final int SESSIONS = 3;
    /** 假 serve 的 prompt_async 应答延迟：旧单线程派发下发送时刻两两间隔 ≈ 此值。 */
    private static final long PROMPT_DELAY_MS = 1_200L;
    /** 旧串行行为的最小到达间隔是 PROMPT_DELAY_MS；并行派发下同批到达间隔接近 0。 */
    private static final long MAX_PARALLEL_GAP_NANOS = 800_000_000L;

    private Path root;
    private final List<HttpServer> fakeServers = new ArrayList<>();
    private int basePort;
    private OpenCodeServeAdapter adapter;
    /** prompt_async 到达时刻（nanoTime），全部 fake serve 共享一份。 */
    private final CopyOnWriteArrayList<Long> promptArrivals = new CopyOnWriteArrayList<>();
    private final CountDownLatch threePrompts = new CountDownLatch(SESSIONS);

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-opencode-dispatch-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        JdbcAgentConfigRepository agentConfigs = new JdbcAgentConfigRepository(jdbc);
        JdbcSessionRepository sessions =
                new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("oc-dispatch", "Dispatch Test", AgentCli.OPENCODE,
                "manual", "opencode/test-model", null, List.of(), "test"), now);
        for (int i = 1; i <= SESSIONS; i++) {
            new JdbcTicketRepository(jdbc).insert(new Ticket("OPEN-D" + i, "t" + i,
                    "refs/heads/main", root.resolve("clone-" + i).toString(), null, null, null, null,
                    TicketStage.IN_PROGRESS, now, now));
            Files.createDirectories(root.resolve("clone-" + i));
        }
        basePort = findFreePort();
        for (int i = 0; i < SESSIONS; i++) {
            startFakeServe(basePort + i);
        }
        adapter = new OpenCodeServeAdapter(new ProcessRunnerImpl(root.resolve("proc")),
                agentConfigs, sessions, new JdbcTicketRepository(jdbc),
                new JdbcGateTaskRepository(jdbc, new SystemClock()), ticketLocks,
                new SystemClock(), new PortAllocator(basePort, basePort + SESSIONS - 1), "", 60,
                gate.adapters.io.AdapterLog.at(Path.of("target", "dispatch-test-adapters.log")));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (adapter != null) {
            adapter.close();
        }
        for (HttpServer server : fakeServers) {
            server.stop(0);
        }
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    @Test
    void three_sessions_dispatch_concurrently_not_serialized() throws Exception {
        List<String> sids = new ArrayList<>();
        for (int i = 1; i <= SESSIONS; i++) {
            Session session = adapter.start(new AgentSessionPort.StartRequest(
                    "OPEN-D" + i, "oc-dispatch", root.resolve("clone-" + i).toString(),
                    "refs/heads/main", "", Map.of()));
            sids.add(session.id());
        }

        // 三个会话同时发消息：提交即刻返回，真正的发送在各自会话队列上并行执行。
        for (String sid : sids) {
            adapter.sendMessage(new AgentSessionPort.SendRequest(sid, "hi", true));
        }

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && promptArrivals.size() < SESSIONS) {
            Thread.sleep(50);
        }
        assertTrue(promptArrivals.size() == SESSIONS,
                "三个会话的 prompt_async 都必须受理: " + promptArrivals.size());
        List<Long> sorted = new ArrayList<>(promptArrivals);
        sorted.sort(Long::compare);
        long gap = sorted.get(1) - sorted.get(0);
        assertTrue(gap < MAX_PARALLEL_GAP_NANOS,
                "不同会话的发送必须并行（前两笔到达间隔 " + gap / 1_000_000 + "ms，"
                        + "串行派发下应 ≈ " + PROMPT_DELAY_MS + "ms）");
    }

    private void startFakeServe(int port) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/session", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = "{\"id\":\"sess-1\"}".getBytes(StandardCharsets.UTF_8);
            if (path.endsWith("/prompt_async")) {
                promptArrivals.add(System.nanoTime());
                threePrompts.countDown();
                try {
                    Thread.sleep(PROMPT_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/event", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            try {
                new CountDownLatch(1).await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // server shutting down
            } finally {
                os.close();
            }
        });
        server.start();
        fakeServers.add(server);
    }

    private static int findFreePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
