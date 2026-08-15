package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.ports.AgentSessionPort;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.ProviderRepository;
import gate.ports.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S3 ClaudeHeadlessAdapter tests (执行文档-后端-web §9.2): a fake {@code claude} stub emits
 * stream-json and the adapter parses session id / message / usage into the session store.
 */
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
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"hello from claude"}]},"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}
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
}
