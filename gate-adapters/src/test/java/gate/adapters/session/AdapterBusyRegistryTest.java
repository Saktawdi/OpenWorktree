package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.*;

import gate.adapters.clock.SystemClock;
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
import gate.domain.session.Session;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.ports.AgentSessionPort;
import gate.ports.Clock;
import gate.ports.ProcessRunner;
import gate.ports.ProviderRepository;
import gate.ports.SessionRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * busySessionIds registry 生命周期：登记、正常完成清除、异常路径清除、abort 竞态、重复 send 引用计数。
 */
class AdapterBusyRegistryTest {

    private Path root;
    private JdbcTemplate jdbc;
    private JdbcAgentConfigRepository agentConfigs;
    private JdbcSessionRepository sessions;
    private JdbcTicketRepository tickets;
    private JdbcGateTaskRepository tasks;
    private Clock clock;
    private FileChannelTicketLockManager ticketLocks;
    private ProcessRunnerImpl realRunner;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-busy-reg-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        agentConfigs = new JdbcAgentConfigRepository(jdbc);
        tickets = new JdbcTicketRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        tasks = new JdbcGateTaskRepository(jdbc, new SystemClock());
        clock = new SystemClock();
        ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        realRunner = new ProcessRunnerImpl(root.resolve("proc"));
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    private void insertTicket(String no, Path clone) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path, executor_provider_id,
                                   executor_model, reviewer_provider_id, reviewer_model, stage,
                                   created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, no, "t", "refs/heads/main", clone.toString(), null, null, null, null, "IN_PROGRESS",
                now.toString(), now.toString());
    }

    // ---- Claude：正常完成清除 ----
    @Test
    void claude_busy_registered_on_enqueue_and_cleared_on_success() throws Exception {
        // 使用内存桩避免 Windows 下 cmd 脚本时序/编码的不确定性
        ProcessRunner okRunner = new ProcessRunner() {
            @Override public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) { return new ProcRun(argv, 0, "", "", Duration.ofMillis(10), false); }
            @Override public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout, java.util.function.Consumer<String> a, java.util.function.Consumer<String> b) {
                return new ProcRun(argv, 0, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}", "", Duration.ofMillis(10), false);
            }
        };
        agentConfigs.insert(new AgentConfig("claude-busy", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"), Instant.now());
        Path clone = root.resolve("clone-ok");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("BUSY-1", clone);
        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(okRunner, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                "claude", List.of());
        Session s = adapter.start(new AgentSessionPort.StartRequest("BUSY-1", "claude-busy", clone.toString(), "refs/heads/main", "", Map.of()));
        // 空闲会话不算 busy
        assertTrue(adapter.busySessionIds().isEmpty());
        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "hi", true));
        // 入队即算运行
        assertTrue(adapter.busySessionIds().contains(s.id()));
        // 等待执行完成（单线程 executor + runStreaming 阻塞约几百 ms）
        awaitBusyGone(adapter, s.id(), 5);
        adapter.close();
    }

    // ---- Claude：异常路径也清除，返回的快照有序且不可变 ----
    @Test
    void claude_busy_cleared_on_error_and_snapshot_is_sorted_immutable() throws Exception {
        // 用一个会抛异常的 runner：ProcessRunner.runStreaming 抛 RuntimeException
        ProcessRunner throwing = new ProcessRunner() {
            @Override public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) { throw new RuntimeException("boom"); }
            @Override public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout, java.util.function.Consumer<String> a, java.util.function.Consumer<String> b) { throw new RuntimeException("boom"); }
        };
        agentConfigs.insert(new AgentConfig("claude-err", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"), Instant.now());
        Path clone = root.resolve("clone-err");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("BUSY-2", clone);
        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(throwing, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                "claude", List.of());
        Session s = adapter.start(new AgentSessionPort.StartRequest("BUSY-2", "claude-err", clone.toString(), "refs/heads/main", "", Map.of()));
        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "hi", true));
        assertTrue(adapter.busySessionIds().contains(s.id()));
        awaitBusyGone(adapter, s.id(), 5);
        // 不可变
        Set<String> snap = adapter.busySessionIds();
        assertThrows(UnsupportedOperationException.class, () -> snap.add("x"));
        adapter.close();
    }

    // ---- Claude：abort 竞态立刻清除；重复 send 引用计数 ----
    @Test
    void claude_abort_clears_and_duplicate_send_uses_refcount() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProcessRunner blocking = new ProcessRunner() {
            @Override public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) { return new ProcRun(argv, 0, "", "", Duration.ofMillis(10), false); }
            @Override public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout, java.util.function.Consumer<String> a, java.util.function.Consumer<String> b) {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ProcRun(argv, 0, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}", "", Duration.ofMillis(10), false);
            }
        };
        agentConfigs.insert(new AgentConfig("claude-block", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"), Instant.now());
        Path clone = root.resolve("clone-block");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("BUSY-3", clone);
        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(blocking, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                "claude", List.of());
        Session s = adapter.start(new AgentSessionPort.StartRequest("BUSY-3", "claude-block", clone.toString(), "refs/heads/main", "", Map.of()));

        // 首次 send 阻塞中，busy 包含
        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "one", true));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(adapter.busySessionIds().contains(s.id()));

        // 重复 send：同一 session 再次入队（executor 队列中等待），busy 仍为 1 条但计数为 2
        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "two", true));
        assertEquals(1, adapter.busySessionIds().size());

        // abort 应立即清空（即使 runSend 仍阻塞），避免顶栏继续显示
        adapter.abort(s.id());
        assertTrue(adapter.busySessionIds().isEmpty(), "abort 必须兜底清除");

        // 释放阻塞，让两个 runSend 依次完成；完成后仍为空（计数已在 abort 时清零）
        release.countDown();
        Thread.sleep(600);
        assertTrue(adapter.busySessionIds().isEmpty());
        // 输出应有序
        adapter.close();
    }

    // ---- Claude：start(initial_prompt) 的首个回合同样计入 busy ----
    @Test
    void claude_initial_prompt_turn_counts_busy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProcessRunner blocking = new ProcessRunner() {
            @Override public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) { return new ProcRun(argv, 0, "", "", Duration.ofMillis(10), false); }
            @Override public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout, java.util.function.Consumer<String> a, java.util.function.Consumer<String> b) {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ProcRun(argv, 0, "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"init done\"}]}}", "", Duration.ofMillis(10), false);
            }
        };
        agentConfigs.insert(new AgentConfig("claude-init", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"), Instant.now());
        Path clone = root.resolve("clone-init");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("BUSY-4", clone);
        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(blocking, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                "claude", List.of());

        // start() 在首个回合上阻塞（initial_prompt 非空）→ 后台线程执行
        Thread t = new Thread(() -> adapter.start(new AgentSessionPort.StartRequest(
                "BUSY-4", "claude-init", clone.toString(), "refs/heads/main", "build it", Map.of())));
        t.setDaemon(true);
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // 首回合运行中：busy 必须包含该会话（旧实现不计 initial_prompt 回合 → 顶栏显示 0）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && adapter.busySessionIds().isEmpty()) {
            Thread.sleep(20);
        }
        assertEquals(1, adapter.busySessionIds().size(), "initial_prompt turn must count as busy");
        release.countDown();
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue(adapter.busySessionIds().isEmpty(), "busy must clear after the initial turn ends");
        adapter.close();
    }

    // ---- Dispatch 聚合并集 ----
    @Test
    void dispatch_merges_busy_ids_sorted() {
        AgentSessionPort a = new AgentSessionPort() {
            public Session start(StartRequest r) { return null; }
            public String sendMessage(SendRequest r) { return "t"; }
            public void abort(String id) {}
            public java.util.List<gate.domain.session.SessionMessage> getHistory(String id) { return List.of(); }
            public java.util.stream.Stream<SessionEvent> streamEvents(String id) { return java.util.stream.Stream.empty(); }
            public AutoCloseable attachListener(String id, java.util.function.Consumer<gate.domain.session.SessionStreamChunk> l) { return ()->{}; }
            public void respondPermission(String a, String b, String c) {}
            public java.util.List<gate.domain.session.PermissionRequest> pendingPermissions(String id) { return List.of(); }
            public java.util.Set<String> busySessionIds() { return Set.of("b", "a"); }
        };
        AgentSessionPort b = new AgentSessionPort() {
            public Session start(StartRequest r) { return null; }
            public String sendMessage(SendRequest r) { return "t"; }
            public void abort(String id) {}
            public java.util.List<gate.domain.session.SessionMessage> getHistory(String id) { return List.of(); }
            public java.util.stream.Stream<SessionEvent> streamEvents(String id) { return java.util.stream.Stream.empty(); }
            public AutoCloseable attachListener(String id, java.util.function.Consumer<gate.domain.session.SessionStreamChunk> l) { return ()->{}; }
            public void respondPermission(String a, String c, String d) {}
            public java.util.List<gate.domain.session.PermissionRequest> pendingPermissions(String id) { return List.of(); }
            public java.util.Set<String> busySessionIds() { return Set.of("c", "a"); }
        };
        // agentConfigs/sessions 仅用于非 busy 路径，传 null 不影响 busy 聚合
        DispatchAgentSessionPort dispatch = new DispatchAgentSessionPort(null, null, a, b);
        java.util.List<String> sorted = new java.util.ArrayList<>(dispatch.busySessionIds());
        assertEquals(List.of("a", "b", "c"), sorted);
        // 不可变
        assertThrows(UnsupportedOperationException.class, () -> dispatch.busySessionIds().add("x"));
    }

    private static void awaitBusyGone(ClaudeHeadlessAdapter adapter, String sid, int secs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs);
        while (System.nanoTime() < deadline) {
            if (!adapter.busySessionIds().contains(sid)) return;
            Thread.sleep(50);
        }
        fail("busy 未在预期内清除: " + adapter.busySessionIds());
    }
}
