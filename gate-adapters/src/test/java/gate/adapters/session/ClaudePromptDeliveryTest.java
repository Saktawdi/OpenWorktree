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
import gate.ports.infra.ProcessRunner.StreamSpec;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
 *
 * <p>T-118 起 stdin 不再是一段裸正文，而是 {@code --input-format=stream-json} 要的一行 JSON
 * （{@link ClaudeStreamInput}）：正文进 text 块，图片进 image 块。断言相应地从"比对 stdin 原文"
 * 改成"解出 text 块比对"，图片另有一条用例盯着不得进 argv。
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

        assertEquals(MULTILINE, stdinText(runner.stdin), "多行消息必须整段经 stdin 送达 CLI");
        assertTrue(runner.argv.contains("--input-format"), "输入得声明 stream-json: " + runner.argv);
        assertEquals(1, runner.stdin.lines().count(), "整行输入只有一个物理换行（行尾）");
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

        assertEquals(QUOTE_ONLY, stdinText(runner.stdin));
        assertTrue(runner.stdin.contains("⟦引用⟧"), "引用标记必须活着到达 CLI（此前整条消息只剩空首行）");
        adapter.close();
    }

    // ---- T-118：图片附件编成 image 块随正文同行进 stdin，base64 绝不进 argv ----
    @Test
    void image_attachment_travels_as_content_block_never_argv() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "DELIV-4");
        Session s = adapter.start(new AgentSessionPort.StartRequest(
                "DELIV-4", "claude-deliv", clone("DELIV-4").toString(), "refs/heads/main", "", Map.of()));

        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        adapter.sendMessage(new AgentSessionPort.SendRequest(s.id(), "看图\n[图片引用 #1] .gate/chat-images/a.png", true,
                List.of(new AgentSessionPort.Attachment("a.png", "image/png", b64))));
        awaitDelivery(runner, 5);

        // 正文那半：路径行与引用行照旧整段送达
        assertEquals("看图\n[图片引用 #1] .gate/chat-images/a.png", stdinText(runner.stdin));
        // 图片那半：content 里多一个 image 块，裸 base64（无 data-URL 前缀）
        List<Map<String, Object>> images = stdinImages(runner.stdin);
        assertEquals(1, images.size(), "附件应编成一个 image 块: " + runner.stdin);
        Map<String, Object> source = castMap(images.get(0).get("source"));
        assertEquals("base64", source.get("type"));
        assertEquals("image/png", source.get("media_type"));
        assertEquals(b64, source.get("data"));
        // 整条输入仍是单行 JSON（按行读的 stream-json 不能被正文换行破帧），图片字节不过 argv
        assertEquals(1, runner.stdin.lines().count());
        assertFalse(runner.argv.stream().anyMatch(a -> a.contains(b64)), "图片 base64 不得进 argv: " + runner.argv);
        adapter.close();
    }

    // ---- start(initial_prompt) 的首个回合走同一条通道 ----
    @Test
    void initial_prompt_turn_also_travels_on_stdin() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        ClaudeHeadlessAdapter adapter = adapter(runner, "DELIV-3");
        adapter.start(new AgentSessionPort.StartRequest(
                "DELIV-3", "claude-deliv", clone("DELIV-3").toString(), "refs/heads/main", MULTILINE, Map.of()));

        assertEquals(MULTILINE, stdinText(runner.stdin), "首回合 initial_prompt 同样必须整段经 stdin 送达");
        assertFalse(runner.argv.stream().anyMatch(a -> a.contains("\n")), "argv 不得含多行元素: " + runner.argv);
        adapter.close();
    }

    // ---- stdin 解码助手：T-118 后 stdin 是一行 stream-json，正文在 text 块里 ----

    /** 取出 stdin 那行 JSON 里所有 text 块拼回的正文（用例里都只有一个）。 */
    @SuppressWarnings("unchecked")
    private static String stdinText(String stdin) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : stdinContent(stdin)) {
            if ("text".equals(block.get("type"))) {
                sb.append(block.get("text"));
            }
        }
        return sb.toString();
    }

    /** 取出 stdin 那行 JSON 里的 image 块。 */
    private static List<Map<String, Object>> stdinImages(String stdin) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> block : stdinContent(stdin)) {
            if ("image".equals(block.get("type"))) {
                out.add(block);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stdinContent(String stdin) {
        Map<String, Object> envelope = gate.application.util.MiniJson.parseObject(stdin.trim());
        return (List<Map<String, Object>>) (List<?>) castMap(envelope.get("message")).get("content");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
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
                                    StreamSpec spec, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
            this.argv = List.copyOf(argv);
            this.stdin = spec.stdin();
            return new ProcRun(argv, 0,
                    "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}",
                    "", Duration.ofMillis(1), false);
        }
    }
}
