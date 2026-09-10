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
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 回合以 {@code result.is_error} 收尾时，本回合<b>已经产出的助手正文必须先落库</b>，错误行接在后面。
 *
 * <p>旧实现把「有正文」和「有错误」写成 if/else 互斥：先吐正文、再以 is_error 收尾的回合（网关 4xx
 * 的典型形态）走的是 error 分支，正文整段静默丢弃——CLI 自己的转录里留着，门禁历史里没有。实测会话
 * e2795003：20:41:46 / 20:42:32 两条助手文本只剩一条 ERROR 行（gate_task 记 SUCCEEDED）。
 *
 * <p>同时盯住两个不许改坏的边界：错误回合没吐一个字时<b>不得</b>凭空多出助手行；回合什么都没产出
 * 且没有错误署名时，仍按「未产生任何输出」报错误气泡。
 *
 * <p>对账兜底（进程猝死整回合全丢）见 {@link ClaudeTranscriptBackfillTest}。
 */
class ClaudeErrorTurnPersistenceTest {

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
        root = Files.createTempDirectory("gate-claude-err-");
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
        agentConfigs.insert(new AgentConfig("claude-err", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"),
                Instant.now());
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    // ---- 核心：正文落库在前，错误行接在后（旧实现这里一个字都不剩） ----
    @Test
    void on_is_error_turn_the_texts_produced_before_the_failure_are_kept() throws Exception {
        String stdout = String.join("\n",
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"cli-err\"}",
                assistant("cli-err", "前半段"),
                assistant("cli-err", "链路确认清楚了，而且 opencode 天然隔离"),
                "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                        + "\"session_id\":\"cli-err\",\"result\":\"API Error: 400 invalid_request_error\","
                        + "\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}");
        ClaudeHeadlessAdapter adapter = adapter(new CannedRunner(stdout), "ERR-1");
        Session s = idleSession(adapter, "ERR-1");

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "问一句", true));
        awaitTurn(adapter, s.id());

        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(4, msgs.size(), "用户 + 两条正文 + 一条错误: " + contents(msgs));
        assertEquals(Role.USER, msgs.get(0).role());
        assertEquals(Role.ASSISTANT, msgs.get(1).role());
        assertEquals("前半段", msgs.get(1).content(), "多行 assistant 逐条落库，顺序即转录顺序");
        assertEquals(Role.ASSISTANT, msgs.get(2).role());
        assertEquals("链路确认清楚了，而且 opencode 天然隔离", msgs.get(2).content());
        assertEquals(Role.ERROR, msgs.get(3).role(), "错误行接在正文之后，不吞正文");
        assertEquals("API Error: 400 invalid_request_error", msgs.get(3).content());
        // 末条正文仍拿到模型归属：正文是一次 run 的产物，收尾方式不该影响它的元数据
        assertNotNull(msgs.get(2).usage(), "末条 assistant 带 usage");
        assertEquals("claude-opus-5", msgs.get(2).modelId());
        // 出错也要记住 CLI 会话 id，否则下一回合 --resume 会另起炉灶
        assertEquals("cli-err", sessions.find(s.id()).orElseThrow().cliSessionId());
        adapter.close();
    }

    // ---- 边界：错误回合没吐正文时不得凭空多出助手行 ----
    @Test
    void on_is_error_turn_without_text_only_the_error_row_is_persisted() throws Exception {
        String stdout = "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                + "\"session_id\":\"cli-err\",\"result\":\"API Error: 429 rate limited\"}";
        ClaudeHeadlessAdapter adapter = adapter(new CannedRunner(stdout), "ERR-2");
        Session s = idleSession(adapter, "ERR-2");

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "问一句", true));
        awaitTurn(adapter, s.id());

        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(2, msgs.size(), "只有用户消息与错误行: " + contents(msgs));
        assertEquals(Role.USER, msgs.get(0).role());
        assertEquals(Role.ERROR, msgs.get(1).role());
        assertEquals("API Error: 429 rate limited", msgs.get(1).content());
        adapter.close();
    }

    // ---- 边界：静默空回合（exit 0、零输出、无 is_error）仍按「未产生任何输出」报错 ----
    @Test
    void silent_turn_still_reports_no_output() throws Exception {
        ClaudeHeadlessAdapter adapter = adapter(new CannedRunner(""), "ERR-3");
        Session s = idleSession(adapter, "ERR-3");

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "问一句", true));
        awaitTurn(adapter, s.id());

        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(2, msgs.size(), "只有用户消息与错误行: " + contents(msgs));
        assertEquals(Role.ERROR, msgs.get(1).role());
        assertTrue(msgs.get(1).content().contains("未产生任何输出"), msgs.get(1).content());
        adapter.close();
    }

    /** 一回合两行 assistant：第二行带 model/usage（CLI 真实形态）。 */
    private static String assistant(String cliSessionId, String text) {
        return "{\"type\":\"assistant\",\"session_id\":\"" + cliSessionId + "\",\"message\":{\"role\":\"assistant\","
                + "\"model\":\"claude-opus-5\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}}";
    }

    private static List<String> contents(List<SessionMessage> msgs) {
        return msgs.stream().map(m -> m.role() + ":" + m.content()).toList();
    }

    private Session idleSession(ClaudeHeadlessAdapter adapter, String ticketNo) throws Exception {
        Path clone = root.resolve("clone-" + ticketNo);
        Files.createDirectories(clone.resolve(".git"));
        insertTicket(ticketNo, clone);
        return adapter.start(new AgentSessionPort.StartRequest(
                ticketNo, "claude-err", clone.toString(), "refs/heads/main", "", Map.of()));
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

    /** 回合跑在 executor 上；busy 清零即本回合全部出口（含落库）都已走完。 */
    private static void awaitTurn(ClaudeHeadlessAdapter adapter, String sessionId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!adapter.busySessionIds().contains(sessionId)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("回合未在预期内结束");
    }

    /** 固定 stdout 的假 claude：一条命令一次输出，exit 0（is_error 也是 exit 0）。 */
    private static final class CannedRunner implements ProcessRunner {

        private final String stdout;

        CannedRunner(String stdout) {
            this.stdout = stdout;
        }

        @Override
        public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) {
            return new ProcRun(argv, 0, "", "", Duration.ofMillis(1), false);
        }

        @Override
        public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                    StreamSpec spec, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
            return new ProcRun(argv, 0, stdout, "", Duration.ofMillis(1), false);
        }
    }
}
