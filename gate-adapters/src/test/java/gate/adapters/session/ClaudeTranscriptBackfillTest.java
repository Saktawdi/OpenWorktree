package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.*;

import gate.adapters.clock.SystemClock;
import gate.adapters.io.AdapterLog;
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
import gate.domain.session.SessionUsage;
import gate.ports.infra.Clock;
import gate.ports.infra.ProcessRunner;
import gate.ports.infra.ProcessRunner.StreamSpec;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CLAUDE 链路的历史对账回填（{@link ClaudeTranscriptBackfill}）：CLI 转录里留着、门禁从没落库的
 * 助手正文，在下一次发消息前按转录原时间戳补回历史。
 *
 * <p>丢内容的路径：进程猝死/断电（整回合零落盘，实测会话 e2795003 的 20:45–20:49 那段）、网关
 * is_error 收尾（{@link ClaudeErrorTurnPersistenceTest} 修的是新回合的落库，对已经丢掉的历史无能为力）、
 * 被中断的回合（T-120 规则本身不落盘，但 CLI 转录里有）。
 *
 * <p>去重按内容而不是「最新一行的时间戳」：CLAUDE 每回合都新落 ASSISTANT 行，按时间截点会把还没补的
 * 旧空洞顶过去，洞就永远找不回来。
 */
class ClaudeTranscriptBackfillTest {

    private static final String T1 = "2026-01-02T03:04:05Z";
    private static final String T2 = "2026-01-02T03:04:06Z";

    private Path root;
    private JdbcTemplate jdbc;
    private JdbcAgentConfigRepository agentConfigs;
    private JdbcSessionRepository sessions;
    private JdbcTicketRepository tickets;
    private JdbcGateTaskRepository tasks;
    private Clock clock;
    private FileChannelTicketLockManager ticketLocks;
    private Path configRoot;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-claude-backfill-");
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
        agentConfigs.insert(new AgentConfig("claude-bf", "C", AgentCli.CLAUDE, "manual", "m", null, List.of(), "d"),
                Instant.now());
        configRoot = root.resolve("claude-config");
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    // ---- 转录目录名：CLI 的实现细节，非字母数字一律 "-"（认错就当转录不存在） ----
    @Test
    void project_dir_encoding_matches_the_cli_escape() {
        assertEquals("D--project-ai-generate-local-git-ticket-system",
                ClaudeTranscriptBackfill.encodeProjectDir(Path.of("D:/project/ai-generate/local-git-ticket-system")));
        assertEquals("D--x-com-openworktree-desktop",
                ClaudeTranscriptBackfill.encodeProjectDir(Path.of("D:/x/com.openworktree.desktop")),
                "整条绝对路径一起转义，分隔符与点号都变 '-'");
    }

    // ---- 核心：转录里比门禁多出来的正文，按转录原时间戳补回来 ----
    @Test
    void missing_text_is_healed_at_its_transcript_timestamp() throws Exception {
        Path cwd = cloneDir("BF-1");
        Session s = sessionRow("BF-1", "cli-1");
        insertAssistant(s.id(), "第一段", Instant.now());
        writeTranscript(cwd, "cli-1", assistant("cli-1", "第一段", T1), assistant("cli-1", "丢掉的第二段", T2));

        assertEquals(1, backfill().reconcile(s.id(), cwd, "cli-1"), "只补缺的那一条");

        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(2, msgs.size(), "已落库的那条不得重复: " + msgs);
        SessionMessage healed = msgs.stream().filter(m -> m.content().equals("丢掉的第二段"))
                .findFirst().orElseThrow();
        assertEquals(Role.ASSISTANT, healed.role());
        assertEquals(Instant.parse(T2), healed.timestamp(), "补回来的行按转录原时间戳回到时间线上的原位");
        assertEquals("claude-opus-5", healed.modelId(), "模型归属照转录里的 model 字段");
        assertFalse(healed.degraded(), "usage 解出来了就不该标 degraded");
        assertTrue(healed.parts().isEmpty(), "只补正文，时间线部件不回填");
    }

    // ---- 幂等：跑第二次不产生新行 ----
    @Test
    void reconcile_is_idempotent() throws Exception {
        Path cwd = cloneDir("BF-2");
        Session s = sessionRow("BF-2", "cli-1");
        writeTranscript(cwd, "cli-1", assistant("cli-1", "丢掉的第二段", T2));

        assertEquals(1, backfill().reconcile(s.id(), cwd, "cli-1"));
        assertEquals(0, backfill().reconcile(s.id(), cwd, "cli-1"), "第二次没有可补的");
        assertEquals(1, sessions.findMessages(s.id()).size());
    }

    // ---- 同一句话反复出现按多重集配平，不误吞也不重复 ----
    @Test
    void repeated_identical_texts_are_counted_as_a_multiset() throws Exception {
        Path cwd = cloneDir("BF-3");
        Session s = sessionRow("BF-3", "cli-1");
        insertAssistant(s.id(), "好的", Instant.now());
        writeTranscript(cwd, "cli-1",
                assistant("cli-1", "好的", T1), assistant("cli-1", "好的", T1), assistant("cli-1", "好的", T1));

        assertEquals(2, backfill().reconcile(s.id(), cwd, "cli-1"), "转录 3 条、门禁 1 条 → 补 2 条");
        assertEquals(3, sessions.findMessages(s.id()).size());
        assertEquals(0, backfill().reconcile(s.id(), cwd, "cli-1"));
    }

    // ---- 转录不存在（编码认错 / 首次会话 / 换了机器）：降级返回 0，绝不抛 ----
    @Test
    void missing_transcript_is_a_silent_no_op() throws Exception {
        Path cwd = cloneDir("BF-4");
        Session s = sessionRow("BF-4", "cli-1");
        assertEquals(0, backfill().reconcile(s.id(), cwd, "cli-1"));
        assertEquals(0, sessions.findMessages(s.id()).size());
        // cliSessionId 还没拿到（首回合之前）同样直接返回
        assertEquals(0, backfill().reconcile(s.id(), cwd, null));
    }

    // ---- 转录里混着 CLI 自己合成的 user 记录（"Continue from where you left off."）不得灌进历史 ----
    @Test
    void cli_synthetic_user_records_are_never_imported() throws Exception {
        Path cwd = cloneDir("BF-5");
        Session s = sessionRow("BF-5", "cli-1");
        writeTranscript(cwd, "cli-1",
                user("cli-1", "Continue from where you left off.", T1),
                assistant("cli-1", "接着干", T2));

        assertEquals(1, backfill().reconcile(s.id(), cwd, "cli-1"));
        List<SessionMessage> msgs = sessions.findMessages(s.id());
        assertEquals(1, msgs.size(), "只补助手正文: " + msgs);
        assertEquals(Role.ASSISTANT, msgs.get(0).role());
    }

    // ---- 串了会话的记录不认（懒复活/分支场景），宁可漏补不串账 ----
    @Test
    void records_from_another_cli_session_are_ignored() throws Exception {
        Path cwd = cloneDir("BF-6");
        Session s = sessionRow("BF-6", "cli-1");
        writeTranscript(cwd, "cli-1", assistant("cli-other", "别人家的回复", T1));

        assertEquals(0, backfill().reconcile(s.id(), cwd, "cli-1"));
        assertEquals(0, sessions.findMessages(s.id()).size());
    }

    // ---- 接线：下一回合开跑前先对账，补回来的行落在历史原位、且不重复已落库的正文 ----
    @Test
    void next_turn_heals_the_hole_before_the_user_message_is_persisted() throws Exception {
        Path cwd = cloneDir("BF-7");
        insertTicket("BF-7", cwd);
        // 第一回合：CLI 自报会话 id，正文照常落库
        ScriptedRunner runner = new ScriptedRunner(
                assistant("cli-1", "第一段", T1) + "\n{\"type\":\"result\",\"session_id\":\"cli-1\"}",
                assistant("cli-1", "第三段", T2) + "\n{\"type\":\"result\",\"session_id\":\"cli-1\"}");
        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(runner, agentConfigs, sessions, tickets, tasks,
                ticketLocks, clock, "claude", List.of());
        adapter.transcriptBackfill().useConfigRoot(configRoot);
        Session s = adapter.start(new AgentSessionPort.StartRequest(
                "BF-7", "claude-bf", cwd.toString(), "refs/heads/main", "", Map.of()));

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "第一轮", true));
        awaitIdle(adapter, s.id());
        assertEquals("cli-1", sessions.find(s.id()).orElseThrow().cliSessionId(), "CLI 会话 id 已记下");

        // 进程猝死那段：转录里留着，门禁历史里没有（时间戳取当下，落在两条已成史的行之间）
        writeTranscript(cwd, "cli-1",
                assistant("cli-1", "第一段", T1),
                assistant("cli-1", "丢掉的第二段", Instant.now().toString()));

        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "第二轮", true));
        awaitIdle(adapter, s.id());

        List<String> order = sessions.findMessages(s.id()).stream().map(SessionMessage::content).toList();
        assertEquals(List.of("第一轮", "第一段", "丢掉的第二段", "第二轮", "第三段"), order,
                "补回来的行按时间戳落在原位，且已落库的正文不重复");
        adapter.close();
    }

    // ---- helpers ----

    private ClaudeTranscriptBackfill backfill() {
        return new ClaudeTranscriptBackfill(sessions, clock, AdapterLog.noop(), configRoot);
    }

    private Path cloneDir(String ticketNo) throws Exception {
        Path clone = root.resolve("clone-" + ticketNo);
        Files.createDirectories(clone.resolve(".git"));
        return clone;
    }

    /** 直接落一条会话行（对账只用到 id 与 cliSessionId）。 */
    private Session sessionRow(String ticketNo, String cliSessionId) {
        insertTicket(ticketNo, root.resolve("clone-" + ticketNo));
        Session s = new Session(UUID.randomUUID().toString(), ticketNo, "claude-bf", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, cliSessionId, root.resolve("clone-" + ticketNo).toString(), -1,
                Instant.now(), null, SessionUsage.EMPTY, null, false);
        sessions.insert(s);
        return s;
    }

    private void insertAssistant(String sessionId, String text, Instant at) {
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
                text, List.of(), null, true, at));
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

    /** 写一份 CLI 转录（目录名与真实布局一致：{@code <配置根>/projects/<cwd 转义>/<cli 会话 id>.jsonl}）。 */
    private void writeTranscript(Path cwd, String cliSessionId, String... records) throws Exception {
        Path dir = configRoot.resolve("projects").resolve(ClaudeTranscriptBackfill.encodeProjectDir(cwd));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(cliSessionId + ".jsonl"), String.join("\n", records) + "\n",
                StandardCharsets.UTF_8);
    }

    private static String assistant(String cliSessionId, String text, String timestamp) {
        return "{\"type\":\"assistant\",\"sessionId\":\"" + cliSessionId + "\",\"timestamp\":\"" + timestamp + "\","
                + "\"message\":{\"role\":\"assistant\",\"model\":\"claude-opus-5\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"" + text + "\"}],\"usage\":{\"input_tokens\":11,\"output_tokens\":4}}}";
    }

    private static String user(String cliSessionId, String text, String timestamp) {
        return "{\"type\":\"user\",\"sessionId\":\"" + cliSessionId + "\",\"timestamp\":\"" + timestamp + "\","
                + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
    }

    private static void awaitIdle(ClaudeHeadlessAdapter adapter, String sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!adapter.busySessionIds().contains(sessionId)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("回合未在预期内结束");
    }

    /** 按调用次序返回各回合 stdout 的假 claude。 */
    private static final class ScriptedRunner implements ProcessRunner {

        private final List<String> stdouts;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedRunner(String... stdouts) {
            this.stdouts = List.of(stdouts);
        }

        @Override
        public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) {
            return new ProcRun(argv, 0, "", "", Duration.ofMillis(1), false);
        }

        @Override
        public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                    StreamSpec spec, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
            int index = Math.min(calls.getAndIncrement(), stdouts.size() - 1);
            return new ProcRun(argv, 0, stdouts.get(index), "", Duration.ofMillis(1), false);
        }
    }
}
