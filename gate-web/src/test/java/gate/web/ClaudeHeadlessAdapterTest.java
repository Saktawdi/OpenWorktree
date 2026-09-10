package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.clock.SystemClock;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.ProcessRunnerImpl;
import gate.adapters.session.ClaudeHeadlessAdapter;
import gate.adapters.store.JdbcAgentConfigRepository;
import gate.adapters.store.JdbcGateTaskRepository;
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
import gate.ports.session.AgentSessionPort;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.store.ProviderRepository;
import gate.ports.store.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S3 ClaudeHeadlessAdapter tests (执行文档-后端-web §9.2): a fake {@code claude} stub emits
 * stream-json and the adapter parses session id / message / usage into the session store.
 */
@Tag("slow")
class ClaudeHeadlessAdapterTest {

    private Path root;
    private JdbcTemplate jdbc;
    private JdbcAgentConfigRepository agentConfigs;
    private SessionRepository sessions;
    private JdbcTicketRepository ticketRepository;
    private JdbcGateTaskRepository tasks;
    private Clock clock;
    private ProcessRunnerImpl processRunner;
    private BlobStore blobs;
    private FileChannelTicketLockManager ticketLocks;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-claude-test-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        this.jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new gate.adapters.store.JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now),
                now);
        agentConfigs = new JdbcAgentConfigRepository(jdbc);
        ticketRepository = new JdbcTicketRepository(jdbc);
        blobs = new gate.adapters.blob.FsBlobStore(root.resolve("blobs"));
        sessions = new JdbcSessionRepository(jdbc, blobs);
        tasks = new JdbcGateTaskRepository(jdbc, new SystemClock());
        clock = new SystemClock();
        processRunner = new ProcessRunnerImpl(root.resolve("proc"));
        ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    @Test
    void start_parses_stream_json_and_persists_session() throws Exception {
        Path script = root.resolve("fake-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"session","session_id":"sess-abc"}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"hello from claude"}],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}
                """, StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig("claude-test", "Claude Test", AgentCli.CLAUDE,
                "manual", "claude-test", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());

        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-1");

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        AgentSessionPort.StartRequest request = new AgentSessionPort.StartRequest(
                "T-1", "claude-test", clone.toString(), "refs/heads/main",
                "please work", Map.of("GATE_DOMAIN_TOKEN", "tok"));
        Session session = adapter.start(request);

        assertNotNull(session.id());
        assertEquals("sess-abc", session.cliSessionId());
        assertEquals("T-1", session.ticketNo());

        List<SessionMessage> history = sessions.findMessages(session.id());
        assertEquals(2, history.size(), "expected user + assistant messages");
        assertTrue(history.stream().anyMatch(m -> "please work".equals(m.content())));
        SessionMessage assistant = history.stream()
                .filter(m -> m.role() == gate.domain.session.Role.ASSISTANT)
                .findFirst().orElseThrow();
        assertEquals("hello from claude", assistant.content());
        assertEquals(15L, assistant.usage().totalTokens());
    }

    @Test
    void start_delivers_initial_prompt_on_stdin_stream_json_and_never_positionally() throws Exception {
        // T-118/T-121：prompt 不再作为 argv 位置参数——`claude` 被解析成 npm 的 claude.cmd，
        // JDK 对 .cmd 一律 cmd.exe /c 包装，cmd 的命令行在第一个换行处结束，多行 argv 活不到 CLI；
        // 且 T-118 起输入改走 stream-json。start 的首回合与 send 同一条 stdin 通道。
        Path script = root.resolve("args-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo %*>"%~dp0claude-args.txt"
                findstr "." >"%~dp0claude-stdin.txt"
                echo {"type":"session","session_id":"sess-args"}
                """, StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig("claude-args", "Claude Args", AgentCli.CLAUDE,
                "manual", "claude-args", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-4");

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        adapter.start(new AgentSessionPort.StartRequest(
                "T-4", "claude-args", clone.toString(), "refs/heads/main", "please work", Map.of()));

        String args = Files.readString(root.resolve("claude-args.txt"), StandardCharsets.UTF_8);
        assertTrue(args.contains("--input-format stream-json"),
                "stream-json 输入必须钉在 argv 上（stdin 是 JSON 行，不是裸正文）");
        assertTrue(args.contains("--output-format stream-json"));
        assertFalse(args.contains("please work"),
                "prompt 不得再作为位置参数进 argv——多行会被 cmd.exe /c 的换行截断");
        String stdin = Files.readString(root.resolve("claude-stdin.txt"), StandardCharsets.UTF_8);
        assertTrue(stdin.contains("\"please work\""), "prompt 经 stdin 以 stream-json text 块送达: " + stdin);
    }

    @Test
    void start_with_failing_stub_records_error_and_aborts() throws Exception {
        Path script = root.resolve("bad-claude.cmd");
        Files.writeString(script, "@echo off\necho boom >&2\nexit /b 1\n", StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig("claude-bad", "Claude Bad", AgentCli.CLAUDE,
                "manual", "claude-bad", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone2");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-2");

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "T-2", "claude-bad", clone.toString(), "refs/heads/main", "hi", Map.of()));

        assertEquals(gate.domain.session.SessionStatus.ABORTED, session.status());
        List<SessionMessage> history = sessions.findMessages(session.id());
        assertTrue(history.stream().anyMatch(m -> m.role() == gate.domain.session.Role.ERROR));
    }

    @Test
    void start_with_blank_prompt_creates_idle_session_without_spawning_claude() throws Exception {
        Path script = root.resolve("idle-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"session","session_id":"should-not-appear"}
                """, StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig("claude-idle", "Claude Idle", AgentCli.CLAUDE,
                "manual", "claude-idle", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone3");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-3");

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe",
                List.of("/c", script.toString()));
        Session session = adapter.start(new AgentSessionPort.StartRequest(
                "T-3", "claude-idle", clone.toString(), "refs/heads/main", "", Map.of()));

        assertEquals(gate.domain.session.SessionStatus.ACTIVE, session.status());
        assertNull(session.cliSessionId(), "claude must not be spawned for an idle session");
        assertTrue(sessions.findMessages(session.id()).isEmpty(),
                "idle session must not record a first user message");
    }

    @Test
    void send_applies_model_override_effort_and_resume() throws Exception {
        Path script = root.resolve("send-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo %*>"%~dp0claude-args.txt"
                echo {"type":"session","session_id":"sess-next"}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]},"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                """, StandardCharsets.UTF_8);

        // agent_config.provider_id carries an FK — seed the provider first.
        new gate.adapters.store.JdbcProviderRepository(jdbc).upsert(
                new gate.ports.store.ProviderRepository.ProviderRow("prov-a", "Provider A",
                        "http://prov-a", "none", "openai", Instant.now()),
                Instant.now());
        AgentConfig config = new AgentConfig("claude-send", "Claude Send", AgentCli.CLAUDE,
                "prov-a", "prov-a/model-a", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-5");

        Session session = new Session("sess-send-1", "T-5", "claude-send", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, "sess-prev", clone.toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, null, false, "prov-a", "model-b", "high", false, null);
        sessions.insert(session);

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        adapter.sendMessage(new AgentSessionPort.SendRequest("sess-send-1", "go on", true));

        Instant deadline = Instant.now().plusSeconds(15);
        while (sessions.findMessages("sess-send-1").stream().noneMatch(m -> m.role() == Role.ASSISTANT)
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        String args = Files.readString(root.resolve("claude-args.txt"), StandardCharsets.UTF_8);
        assertTrue(args.contains("--resume sess-prev"),
                "per-message spawn must resume the recorded CLI conversation");
        assertTrue(args.contains("--model model-b"),
                "override model reaches claude bare (no provider prefix)");
        assertFalse(args.contains("prov-a/model-b"), "provider half of the ref is gate-internal");
        assertTrue(args.contains("--effort high"),
                "selected variant must pin claude's effort (gateway rejects its default)");
    }

    @Test
    void start_omits_model_flag_for_cli_managed_model_sentinel() throws Exception {
        // cli-default 哨兵（model 与 provider 同名、无斜杠）：模型由 claude 自身配置决定，
        // 不能把字面量 "cli-default" 传给 --model。
        Path script = root.resolve("sentinel-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo %*>"%~dp0claude-args.txt"
                findstr "." >"%~dp0claude-stdin.txt"
                echo {"type":"session","session_id":"sess-sentinel"}
                """, StandardCharsets.UTF_8);

        new gate.adapters.store.JdbcProviderRepository(jdbc).upsert(
                new gate.ports.store.ProviderRepository.ProviderRow("cli-default", "CLI default",
                        "local://cli-default", "none", "cli-runtime", Instant.now()),
                Instant.now());
        AgentConfig config = new AgentConfig("claude-sentinel", "Claude Sentinel", AgentCli.CLAUDE,
                "cli-default", "cli-default", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-6");

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        adapter.start(new AgentSessionPort.StartRequest(
                "T-6", "claude-sentinel", clone.toString(), "refs/heads/main", "hello", Map.of()));

        String args = Files.readString(root.resolve("claude-args.txt"), StandardCharsets.UTF_8);
        assertFalse(args.contains("--model"), "cli-managed sentinel must not pin --model");
        String stdin = Files.readString(root.resolve("claude-stdin.txt"), StandardCharsets.UTF_8);
        assertTrue(stdin.contains("\"hello\""), "prompt still reaches claude, on stdin as stream-json: " + stdin);
    }

    @Test
    void send_pins_persisted_permission_mode_and_falls_back_to_acceptEdits() throws Exception {
        // V24 权限模式轮询：会话持久化的档位钉进 --permission-mode，null 回退 acceptEdits
        // （= 引入本列前的硬编码默认，存量会话行为不变）。
        Path script = root.resolve("perm-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo %*>"%~dp0claude-args.txt"
                echo {"type":"session","session_id":"sess-perm"}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]}}
                """, StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig("claude-perm", "Claude Perm", AgentCli.CLAUDE,
                "manual", "manual", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-9");
        // 第一档：持久化 bypassPermissions。
        sessions.insert(new Session("sess-perm-1", "T-9", "claude-perm", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, null, clone.toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, null, false, null, null, null, false, "bypassPermissions"));

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        adapter.sendMessage(new AgentSessionPort.SendRequest("sess-perm-1", "go", true));
        awaitAssistant("sess-perm-1");
        String args = Files.readString(root.resolve("claude-args.txt"), StandardCharsets.UTF_8);
        assertTrue(args.contains("--permission-mode bypassPermissions"),
                "persisted mode must pin --permission-mode");

        // 第二档：null（未设置）回退默认。
        Files.delete(root.resolve("claude-args.txt"));
        sessions.insert(new Session("sess-perm-2", "T-9", "claude-perm", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, null, clone.toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, null, false, null, null, null, false, null));
        adapter.sendMessage(new AgentSessionPort.SendRequest("sess-perm-2", "go", true));
        awaitAssistant("sess-perm-2");
        args = Files.readString(root.resolve("claude-args.txt"), StandardCharsets.UTF_8);
        assertTrue(args.contains("--permission-mode acceptEdits"),
                "null mode falls back to acceptEdits (pre-V24 default)");
    }

    @Test
    void send_journals_claude_tasks_live_then_replays_authoritative_ids() throws Exception {
        // V24 任务链：live 阶段 TaskCreate 按 max+1 乐观入 journal（本例 1），回合终态
        // 从 parts 的 result_json 全量重放——真实 id 5 覆盖乐观 id，TaskUpdate 的状态
        // 变更与全字段变更一并落定。journal 是历史的纯投影。真实的 CLAUDE 回合在工具
        // 交换后还有一条带正文的收尾 assistant 行（只有 tool_use 的回合不落库——适配器
        // 只落正文非空的 assistant 行），awaitAssistant 拿它当回合收尾的同步点。
        Path script = root.resolve("task-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"system","subtype":"init","session_id":"sess-task"}
                echo {"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu-1","name":"TaskCreate"}}}
                echo {"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"subject\\":\\"fix the bug\\"}"}}}
                echo {"type":"stream_event","event":{"type":"content_block_stop","index":1}}
                echo {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu-1","name":"TaskCreate","input":{"subject":"fix the bug"}}]}}
                echo {"type":"user","message":{"content":[{"type":"tool_result","content":"Task #5 created successfully: fix the bug"}]}}
                echo {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu-2","name":"TaskUpdate","input":{"taskId":"5","status":"completed"}}]}}
                echo {"type":"user","message":{"content":[{"type":"tool_result","content":"Updated task #5 status"}]}}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"Task #5 created and completed."}]}}
                echo {"type":"result","is_error":false,"usage":{"input_tokens":3,"output_tokens":2}}
                """, StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig("claude-task", "Claude Task", AgentCli.CLAUDE,
                "manual", "manual", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-10");
        sessions.insert(new Session("sess-task-1", "T-10", "claude-task", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, null, clone.toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, null, false));

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        // live 终态帧契约：TaskCreate 的 stop 帧 status=SUCCESS + 携带完整参数——前端
        // stream.ts 据此（d.status === "SUCCESS"）触发 syncSessionTasks 拉取 journal。
        List<gate.domain.session.SessionStreamChunk> chunks =
                Collections.synchronizedList(new ArrayList<>());
        try (AutoCloseable sub = adapter.attachListener("sess-task-1", chunks::add)) {
            adapter.sendMessage(new AgentSessionPort.SendRequest("sess-task-1", "plan it", true));
            awaitAssistant("sess-task-1");
            Instant deadline = Instant.now().plusSeconds(15);
            while (chunks.stream().noneMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.DoneChunk)
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
        }
        // stop 帧（而非 RUNNING 分片帧）携带 SUCCESS + 完整参数：status 字段非 null，
        // 前端的 SUCCESS 门条件在 live 阶段可命中（f1 复核依据，回归锁死）。
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ToolCallChunk tc
                && "TaskCreate".equals(tc.toolName()) && "SUCCESS".equals(tc.status())
                && tc.argumentDelta() != null && tc.argumentDelta().contains("fix the bug")),
                "TaskCreate stop frame must carry SUCCESS + full args (frontend live-sync trigger)");
        assertTrue(chunks.stream().noneMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ToolCallChunk tc
                && "TaskCreate".equals(tc.toolName()) && tc.status() == null),
                "no TaskCreate frame may carry a null status");

        String journal = sessions.findTasks("sess-task-1").orElseThrow();
        assertTrue(journal.contains("\"id\":5"),
                "replay must replace the optimistic id 1 with the authoritative 5: " + journal);
        assertTrue(journal.contains("\"status\":\"completed\""),
                "TaskUpdate status flip must be replayed: " + journal);
        assertTrue(journal.contains("fix the bug"), journal);
        // opencode 的 todowrite 链不受影响：TaskCreate 不是 todo 写工具。
        assertTrue(sessions.findTodos("sess-task-1").isEmpty(),
                "claude task tools must not touch the session_todo chain");
    }

    @Test
    void send_streams_deltas_and_persists_full_turn() throws Exception {
        // claude 流式路径：stream_event 增量实时转发为 SSE chunk（与 opencode 消费契约一致），
        // 终态按 assistant 行落库、result 行取权威 usage——缺一环工作台就全程空白。
        // ASCII-only stub content: cmd.exe reads batch files in the OEM codepage, so UTF-8
        // Chinese in the script would be mangled before claude ever sees it.
        Path script = root.resolve("live-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"system","subtype":"init","session_id":"sess-live"}
                echo {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}}
                echo {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}}
                echo {"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu-1","name":"Bash"}}}
                echo {"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"cmd\\":\\"dir\\"}"}}}
                echo {"type":"stream_event","event":{"type":"content_block_stop","index":1}}
                echo {"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"hello"}}}
                echo {"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"!"}}}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"hello!"}]}}
                echo {"type":"result","is_error":false,"usage":{"input_tokens":10,"output_tokens":5}}
                """, StandardCharsets.UTF_8);

        new gate.adapters.store.JdbcProviderRepository(jdbc).upsert(
                new gate.ports.store.ProviderRepository.ProviderRow("manual", "manual",
                        "local://manual", "none", "manual", Instant.now()),
                Instant.now());
        AgentConfig config = new AgentConfig("claude-live", "Claude Live", AgentCli.CLAUDE,
                "manual", "manual", null, List.of(), "test");
        agentConfigs.insert(config, Instant.now());
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-7");
        sessions.insert(new Session("sess-live-1", "T-7", "claude-live", AgentCli.CLAUDE,
                SessionStatus.ACTIVE, null, clone.toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, null, false));

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock, "cmd.exe", List.of("/c", script.toString()));
        List<gate.domain.session.SessionStreamChunk> chunks =
                Collections.synchronizedList(new ArrayList<>());
        try (AutoCloseable sub = adapter.attachListener("sess-live-1", chunks::add)) {
            adapter.sendMessage(new AgentSessionPort.SendRequest("sess-live-1", "hi", true));
            Instant deadline = Instant.now().plusSeconds(15);
            while (chunks.stream().noneMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.DoneChunk)
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
        }

        // 增量实时到达：文本分片、思考、工具生命周期、用量。
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ContentChunk t
                && "hello".equals(t.textDelta())));
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ContentChunk t
                && "!".equals(t.textDelta())));
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ThinkingChunk t
                && "hmm".equals(t.thinkingDelta())));
        // start 帧不带参数（null）：字面 "{}" 会给前端累积缓冲垫非法前缀（T-110 黑盒根因）。
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ToolCallChunk tc
                && "RUNNING".equals(tc.status()) && "Bash".equals(tc.toolName()) && tc.argumentDelta() == null));
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ToolCallChunk tc
                && tc.argumentDelta() != null && tc.argumentDelta().contains("cmd")));
        // stop 帧携带拼齐的完整参数 JSON：前端按快照替换定格，live 卡片 IN 区不再空白。
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.ToolCallChunk tc
                && "SUCCESS".equals(tc.status()) && "{\"cmd\":\"dir\"}".equals(tc.argumentDelta())));
        assertTrue(chunks.stream().anyMatch(c -> c instanceof gate.domain.session.SessionStreamChunk.UsageChunk u
                && u.usage() != null && u.usage().totalTokens() == 15L));

        // 终态落库：全文一条助手消息，usage 来自 result 行，cli 会话 id 回写。
        Session done = sessions.find("sess-live-1").orElseThrow();
        assertEquals("sess-live", done.cliSessionId());
        assertEquals(15L, done.cumulativeUsage().totalTokens());
        SessionMessage assistant = sessions.findMessages("sess-live-1").stream()
                .filter(m -> m.role() == Role.ASSISTANT).findFirst().orElseThrow();
        assertEquals("hello!", assistant.content());
    }

    @Test
    void assistant_row_records_reported_model_over_request_fallback() throws Exception {
        // V22 逐消息模型标注：CLI 在 assistant/result 行上报了实际模型 → 以实际上报为准
        // （即使与请求值不同，网关路由后的真实模型优先）。
        Path script = root.resolve("reported-model.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"session","session_id":"sess-rep"}
                echo {"type":"assistant","message":{"model":"gateway/routed-model","content":[{"type":"text","text":"ok"}]}}
                echo {"type":"result","is_error":false,"model":"gateway/routed-model","usage":{"input_tokens":1,"output_tokens":1}}
                """, StandardCharsets.UTF_8);
        seedProviderAgentAndSession("rm-prov", "rm-agent", "sess-rep-1", script);
        ClaudeHeadlessAdapter adapter = adapterFor(script);
        adapter.sendMessage(new AgentSessionPort.SendRequest("sess-rep-1", "hi", true));
        SessionMessage assistant = awaitAssistant("sess-rep-1");
        assertEquals("gateway", assistant.modelProvider(), "reported provider wins");
        assertEquals("routed-model", assistant.modelId(), "reported model id wins");
        // V23：本用例未选推理档位（CLI 也不回传）→ 落 null，前端回退近似标注。
        assertNull(assistant.reasoningVariant(), "no variant pinned means no attribution");
    }

    @Test
    void assistant_row_falls_back_to_requested_model_when_upstream_silent() throws Exception {
        // V22：CLI 未上报 model 字段（旧版 CLI）→ 回退发送端请求值（--model 钉住的裸 id，
        // provider 取 AgentConfig ref 的 provider 段）。
        Path script = root.resolve("silent-model.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"session","session_id":"sess-silent"}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"ok"}]}}
                echo {"type":"result","is_error":false,"usage":{"input_tokens":1,"output_tokens":1}}
                """, StandardCharsets.UTF_8);
        seedProviderAgentAndSession("silent-prov", "silent-agent", "sess-silent-1", script,
                "silent-prov/req-model", "high");
        ClaudeHeadlessAdapter adapter = adapterFor(script);
        adapter.sendMessage(new AgentSessionPort.SendRequest("sess-silent-1", "hi", true));
        SessionMessage assistant = awaitAssistant("sess-silent-1");
        assertEquals("silent-prov", assistant.modelProvider(), "request provider half");
        assertEquals("req-model", assistant.modelId(), "request bare id is the fallback");
        // V23：会话推理档位覆盖随行落库（--effort 钉住的请求值）。
        assertEquals("high", assistant.reasoningVariant(), "pinned effort rides along");
    }

    private void insertTicket(String ticketNo) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path, executor_provider_id,
                                   executor_model, reviewer_provider_id, reviewer_model, stage,
                                   created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                ticketNo, "t", "refs/heads/main", root.resolve("clone").toString(),
                null, null, null, null, "IN_PROGRESS", now.toString(), now.toString());
    }

    /** V22/V23 标注测试的最小种子：provider 行 + AgentConfig（默认 ref）+ 带 .git 的克隆 + 工单 + 会话。
     *  agentModelRef[0] = Agent 默认模型 ref；agentModelRef[1] = 会话推理档位覆盖（可省）。 */
    private void seedProviderAgentAndSession(String providerId, String agentId, String sessionId,
                                             Path script, String... agentModelRef) throws Exception {
        Instant now = Instant.now();
        new gate.adapters.store.JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow(providerId, providerId,
                        "local://" + providerId, "none", "manual", now), now);
        String modelRef = agentModelRef.length > 0 ? agentModelRef[0] : providerId + "/default-model";
        agentConfigs.insert(new AgentConfig(agentId, agentId, AgentCli.CLAUDE,
                providerId, modelRef, null, List.of(), "test"), now);
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));
        insertTicket("T-88");
        // 覆盖用：fallback 用例把 AgentConfig 默认 ref 设为请求值本身——
        // 会话未切覆盖（override_* 为 NULL）时 buildArgv 即钉该值。
        String variant = agentModelRef.length > 1 ? agentModelRef[1] : null;
        sessions.insert(new Session(sessionId, "T-88", agentId, AgentCli.CLAUDE,
                SessionStatus.ACTIVE, sessionId.equals("sess-rep-1") ? "sess-rep" : "sess-silent",
                clone.toString(), -1, now, null,
                SessionUsage.EMPTY, null, false, null, null, variant, false, null));
    }

    private ClaudeHeadlessAdapter adapterFor(Path script) {
        return new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepository, tasks, ticketLocks, clock,
                "cmd.exe", List.of("/c", script.toString()));
    }

    private SessionMessage awaitAssistant(String sessionId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            var found = sessions.findMessages(sessionId).stream()
                    .filter(m -> m.role() == Role.ASSISTANT).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("assistant row never persisted for " + sessionId);
    }
}
