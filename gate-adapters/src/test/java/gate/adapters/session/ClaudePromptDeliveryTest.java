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
import gate.domain.session.Session;
import gate.ports.infra.Clock;
import gate.ports.infra.ProcessRunner;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-121：用户消息必须整段（含换行）经 <em>stdin</em> 送达 CLI，绝不能是 argv 元素。
 *
 * <p>Windows 上 claude 被解析到 npm 的 {@code claude.cmd}，JDK 对 .cmd 一律以
 * {@code cmd.exe /c} 启动，而 cmd 的命令行在第一个换行处结束——多行 argv 元素只有第一行能到达
 * CLI，于是引用（追加在新行）、{@code [图片引用 #n]} 路径行、多行指令全部静默丢失
 * （证据会话 f506fb84：6/6 多行消息截断，单行零失误）。
 */
class ClaudePromptDeliveryTest {

    private Path root;
    private JdbcTemplate jdbc;
    private JdbcAgentConfigRepository agentConfigs;
    private JdbcSessionRepository sessions;
    private JdbcTicketRepository tickets;
    private JdbcGateTaskRepository tasks;
    private Clock clock;
    private FileChannelTicketLockManager ticketLocks;

    /** 多行消息的三种真实形态各占一行：正文、图片引用行（后端追加在尾部）、引用片段。 */
    private static final String MULTILINE = String.join("\n",
            "另外开一个不相干的单：",
            "当前项目的 live 视图有性能问题，t/s 大时带思考块会卡；",
            "[图片引用 #1] .gate/chat-images/20260910-172950-image.png");
    private static final String QUOTE_ONLY = "\n⟦引用⟧当前项目的live视图有性能问题⟦/引用⟧";

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-prompt-delivery-");
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
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    // ---- sendMessage：多行消息整段进 stdin，argv 里不得有多行元素 ----
    @Test
    void multiline_message_travels_on_stdin_never_argv() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "DELIV-1");
        Session s = adapter.start(new AgentSessionPort.StartRequest(
                "DELIV-1", "claude-deliv", clone("DELIV-1").toString(), "refs/heads/main", "", Map.of()));

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), MULTILINE, true));
        awaitDelivery(runner, 5);

        assertEquals(MULTILINE, runner.stdin, "多行消息必须整段经 stdin 送达 CLI");
        assertFalse(runner.argv.stream().anyMatch(a -> a.contains("\n")),
                "argv 不得含任何多行元素（cmd.exe 会在换行处截断）: " + runner.argv);
        assertFalse(runner.argv.contains(MULTILINE), "prompt 不得再作为 positional argv 传递");
        adapter.close();
    }

    // ---- 空首行（纯引用消息）同样整段送达：它曾让 prompt 变空串 → claude exit=1 → 假错误气泡 ----
    @Test
    void quote_only_message_with_empty_first_line_is_delivered_whole() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "DELIV-2");
        Session s = adapter.start(new AgentSessionPort.StartRequest(
                "DELIV-2", "claude-deliv", clone("DELIV-2").toString(), "refs/heads/main", "", Map.of()));

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), QUOTE_ONLY, true));
        awaitDelivery(runner, 5);

        assertEquals(QUOTE_ONLY, runner.stdin);
        assertTrue(runner.stdin.contains("⟦引用⟧"), "引用标记必须活着到达 CLI（此前整条消息只剩空首行）");
        adapter.close();
    }

    // ---- start(initial_prompt) 的首个回合走同一条通道 ----
    @Test
    void initial_prompt_turn_also_travels_on_stdin() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "DELIV-3");
        adapter.start(new AgentSessionPort.StartRequest(
                "DELIV-3", "claude-deliv", clone("DELIV-3").toString(), "refs/heads/main", MULTILINE, Map.of()));

        assertEquals(MULTILINE, runner.stdin, "首回合 initial_prompt 同样必须整段经 stdin 送达");
        assertFalse(runner.argv.stream().anyMatch(a -> a.contains("\n")), "argv 不得含多行元素: " + runner.argv);
        adapter.close();
    }

    private ClaudeHeadlessAdapter adapter(ProcessRunner runner, String ticketNo) throws Exception {
        agentConfigs.insert(new AgentConfig("claude-deliv", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"), Instant.now());
        insertTicket(ticketNo, clone(ticketNo));
        return new ClaudeHeadlessAdapter(runner, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                "claude", List.of());
    }

    private Path clone(String ticketNo) throws Exception {
        Path clone = root.resolve("clone-" + ticketNo);
        Files.createDirectories(clone.resolve(".git"));
        return clone;
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

    private static void awaitDelivery(CapturingRunner runner, int secs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs);
        while (System.nanoTime() < deadline) {
            if (runner.stdin != null) {
                return;
            }
            Thread.sleep(20);
        }
        fail("stdin 未在预期内送达");
    }

    /** 记录最近一次 spawn 的 argv 与 stdin；返回一条最小可解析的 assistant stream-json。 */
    private static final class CapturingRunner implements ProcessRunner {
        volatile List<String> argv;
        volatile String stdin;

        @Override
        public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) {
            return new ProcRun(argv, 0, "", "", Duration.ofMillis(1), false);
        }

        @Override
        public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                    String stdin, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
            this.argv = List.copyOf(argv);
            this.stdin = stdin;
            return new ProcRun(argv, 0,
                    "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}",
                    "", Duration.ofMillis(1), false);
        }
    }
}
