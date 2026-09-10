package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.*;

import gate.adapters.clock.SystemClock;
import gate.adapters.lock.FileChannelTicketLockManager;
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
import gate.domain.session.SessionStatus;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.infra.Clock;
import gate.ports.infra.ProcessRunner;
import gate.ports.infra.ProcessRunner.StreamSpec;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-120：abort 必须真正终止本轮 claude 进程，而不是只把会话标成 ABORTED。
 *
 * <p>语义对齐 OpenCode 的 soft abort：只打断这一轮，会话保持 ACTIVE 可立即继续对话；
 * 被中止的回合不落助手消息、不落错误气泡、任务终态是 CANCELLED。
 */
class ClaudeAbortTest {

    private Path root;
    private JdbcTemplate jdbc;
    private JdbcAgentConfigRepository agentConfigs;
    private JdbcSessionRepository sessions;
    private JdbcTicketRepository tickets;
    private JdbcGateTaskRepository tasks;
    private Clock clock;
    private FileChannelTicketLockManager ticketLocks;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-claude-abort-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        this.jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        agentConfigs = new JdbcAgentConfigRepository(jdbc);
        tickets = new JdbcTicketRepository(jdbc);
        sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        tasks = new JdbcGateTaskRepository(jdbc, new SystemClock());
        clock = new SystemClock();
        ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        agentConfigs.insert(new AgentConfig("claude-abt", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"),
                Instant.now());
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    // ---- 核心：abort 置位取消信号 → runner 杀进程 → 本轮立即结束，不落任何助手/错误消息 ----
    @Test
    void abort_terminates_the_running_turn_and_keeps_the_session_continuable() throws Exception {
        BlockingRunner runner = new BlockingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "ABT-1");
        Session s = idleSession(adapter, "ABT-1");

        String taskId = adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "跑一个长任务", true));
        assertTrue(runner.started.await(5, TimeUnit.SECONDS), "回合应已启动");
        assertTrue(adapter.busySessionIds().contains(s.id()));

        adapter.abort(s.id());

        // 旧实现只改状态，本回合会一直阻塞到 10s 兜底才结束——这里必须立刻返回
        assertTrue(runner.finished.await(5, TimeUnit.SECONDS), "abort 后本回合必须尽快结束（真终止）");
        assertTrue(runner.sawCancel, "abort 必须把取消信号交给 runner，由它杀进程");
        assertTrue(adapter.busySessionIds().isEmpty(), "abort 后顶栏 busy 必须清零");

        Session after = sessions.find(s.id()).orElseThrow();
        assertEquals(SessionStatus.ACTIVE, after.status(), "soft abort：会话保持 ACTIVE，可立即继续对话");
        assertNull(after.finishedAt(), "soft abort 不写 finished_at");

        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(1, msgs.size(), "只应有用户那条消息，不得落助手回复或错误气泡: " + msgs);
        assertEquals(Role.USER, msgs.get(0).role());

        assertEquals(GateTaskStatus.CANCELLED, tasks.find(taskId).orElseThrow().status(),
                "被中止的回合任务终态是 CANCELLED");
        adapter.close();
    }

    // ---- 还在队列里没 spawn 的回合：消息没进过 CLI，整回合作废（不入历史） ----
    @Test
    void abort_drops_a_queued_turn_that_never_started() throws Exception {
        BlockingRunner runner = new BlockingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "ABT-2");
        Session s = idleSession(adapter, "ABT-2");

        String first = adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "第一条", true));
        assertTrue(runner.started.await(5, TimeUnit.SECONDS));
        String second = adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "第二条", true));

        adapter.abort(s.id());
        assertTrue(runner.finished.await(5, TimeUnit.SECONDS));

        awaitTaskTerminal(first, 5);
        awaitTaskTerminal(second, 5);
        assertEquals(GateTaskStatus.CANCELLED, tasks.find(first).orElseThrow().status());
        assertEquals(GateTaskStatus.CANCELLED, tasks.find(second).orElseThrow().status());

        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(1, msgs.size(), "排队中被中断的消息不该进历史: " + msgs);
        assertEquals("第一条", msgs.get(0).content());
        adapter.close();
    }

    // ---- 中断后会话仍可用：下一条消息照常起新回合 ----
    @Test
    void session_accepts_a_new_turn_after_abort() throws Exception {
        BlockingRunner runner = new BlockingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "ABT-3");
        Session s = idleSession(adapter, "ABT-3");

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "第一轮", true));
        assertTrue(runner.started.await(5, TimeUnit.SECONDS));
        adapter.abort(s.id());
        assertTrue(runner.finished.await(5, TimeUnit.SECONDS));

        // 新回合不再被上一轮的取消信号误伤（信号按回合发放，abort 时整体移除）
        String next = adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "第二轮", true));
        assertTrue(runner.started(2), "中断后必须还能起新回合");
        runner.release();
        awaitTaskTerminal(next, 5);
        assertEquals(GateTaskStatus.SUCCEEDED, tasks.find(next).orElseThrow().status(),
                "新回合不该继承上一轮的取消状态");
        assertEquals(SessionStatus.ACTIVE, sessions.find(s.id()).orElseThrow().status());
        adapter.close();
    }

    private Session idleSession(ClaudeHeadlessAdapter adapter, String ticketNo) throws Exception {
        Path clone = root.resolve("clone-" + ticketNo);
        Files.createDirectories(clone.resolve(".git"));
        insertTicket(ticketNo, clone);
        return adapter.start(new AgentSessionPort.StartRequest(
                ticketNo, "claude-abt", clone.toString(), "refs/heads/main", "", Map.of()));
    }

    private ClaudeHeadlessAdapter adapter(ProcessRunner runner, String ticketNo) {
        return new ClaudeHeadlessAdapter(runner, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                "claude", List.of());
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

    private void awaitTaskTerminal(String taskId, int secs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs);
        while (System.nanoTime() < deadline) {
            if (tasks.find(taskId).map(t -> t.status().isTerminal()).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("任务未在预期内到达终态: " + tasks.find(taskId).map(GateTask::status).orElse(null));
    }

    /**
     * 假 claude 进程：启动后一直"运行"，直到收到取消信号才退出——与真实的 {@code claude -p}
     * 被 kill 后立刻结束同形。旧实现（不置位信号）会让它阻塞到 10s 兜底，测试因此能分辨真假终止。
     */
    private static final class BlockingRunner implements ProcessRunner {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        /** 让未被取消的回合也能收尾（测试 3 的"中断后新回合"）。 */
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger starts = new AtomicInteger();
        private volatile boolean sawCancel;

        boolean started(int n) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (starts.get() >= n) {
                    return true;
                }
                Thread.sleep(20);
            }
            return false;
        }

        void release() {
            release.countDown();
        }

        @Override
        public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) {
            return new ProcRun(argv, 0, "", "", Duration.ofMillis(1), false);
        }

        @Override
        public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                    StreamSpec spec, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
            starts.incrementAndGet();
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (spec != null && spec.cancel() != null && spec.cancel().cancelled()) {
                    sawCancel = true;
                    break;
                }
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            finished.countDown();
            // 被中断的回合产出半截流：runSend 必须忽略它，不得当成功解析
            return new ProcRun(argv, 0,
                    "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"半截回复\"}]}}",
                    "", Duration.ofMillis(1), false);
        }
    }
}
