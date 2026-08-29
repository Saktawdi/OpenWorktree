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
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcSessionRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.ticket.Ticket;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.ProviderRepository;
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
 * S5 cost writeback (执行文档-后端-web §5.7, §9.5 A19): after a session starts with parsed usage,
 * {@code ticket.exec_token_total} is populated from the agent CLI and H1 cost ratio can be computed.
 */
class SessionCostWritebackTest {

    private Path root;
    private JdbcTicketRepository tickets;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-cost-writeback-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now),
                now);
        tickets = new JdbcTicketRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    @Test
    void start_writes_agent_cli_tokens_to_ticket() throws Exception {
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        JdbcAgentConfigRepository agentConfigs = new JdbcAgentConfigRepository(jdbc);
        JdbcTicketRepository ticketRepo = new JdbcTicketRepository(jdbc);
        JdbcSessionRepository sessions = new JdbcSessionRepository(jdbc,
                new gate.adapters.blob.FsBlobStore(root.resolve("blobs")));
        JdbcGateTaskRepository tasks = new JdbcGateTaskRepository(jdbc, new SystemClock());
        FileChannelTicketLockManager ticketLocks = new FileChannelTicketLockManager(root.resolve("locks"));
        ProcessRunnerImpl processRunner = new ProcessRunnerImpl(root.resolve("proc"));

        // Seed provider, agent config, ticket.
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now),
                now);
        agentConfigs.insert(new AgentConfig("claude-cost", "Cost", AgentCli.CLAUDE, "manual",
                "claude-test", null, List.of(), "test"), now);
        ticketRepo.insert(new Ticket("COST-1", "cost", "refs/heads/main", root.resolve("clone").toString(),
                null, null, null, null, gate.domain.ticket.TicketStage.IN_PROGRESS, now, now));

        // Fake claude stub emits an assistant message carrying usage (real claude shape:
        // usage lives inside message or on the result line, never top-level).
        Path script = root.resolve("fake-claude.cmd");
        Files.writeString(script, """
                @echo off
                echo {"type":"session","session_id":"sess-cost"}
                echo {"type":"assistant","message":{"content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}
                """, StandardCharsets.UTF_8);
        Path clone = root.resolve("clone");
        Files.createDirectories(clone.resolve(".git"));

        ClaudeHeadlessAdapter adapter = new ClaudeHeadlessAdapter(processRunner, agentConfigs,
                sessions, ticketRepo, tasks, ticketLocks, new SystemClock(), "cmd.exe",
                List.of("/c", script.toString()));
        adapter.start(new AgentSessionPort.StartRequest("COST-1", "claude-cost", clone.toString(),
                "refs/heads/main", "hello", Map.of("GATE_DOMAIN_TOKEN", "tok")));

        Ticket after = ticketRepo.find("COST-1").orElseThrow();
        assertNotNull(after.execTokenTotal());
        assertEquals(15L, after.execTokenTotal().longValue());
        assertEquals("agent_cli", after.execTokenSource());
        assertTrue(after.updatedAt() != null);

        // Seed a review result with review tokens so H1's cost ratio denominator is complete.
        seedReviewResult(jdbc);

        gate.application.metrics.MetricsService metrics = new gate.application.metrics.MetricsService(
                new gate.adapters.store.JdbcReviewResultRepository(jdbc),
                new gate.adapters.store.JdbcPresubmitRepository(jdbc),
                ticketRepo);
        double costRatio = gate.application.metrics.MetricsService.computeCostRatioMedian(metrics.export());
        assertTrue(!Double.isNaN(costRatio),
                "cost ratio must be non-NaN after agent_cli writeback, got " + costRatio);
    }

    private static void seedReviewResult(JdbcTemplate jdbc) {
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO presubmit(id, ticket_no, review_round, tree_hash, base_commit, target_ref,
                                      diff_blob, diff_bytes, diff_sha256, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, 1, "COST-1", 1, "a".repeat(40), "b".repeat(40), "refs/heads/main",
                "blob", 10, "0".repeat(64), now);
        jdbc.update("""
                INSERT INTO review_result(id, presubmit_id, engine_id, engine_version, provider_id,
                                          model_name, verdict, findings_blob, covered_ok, degraded,
                                          raw_blob, created_at, prompt_tokens, completion_tokens,
                                          total_tokens, token_source, review_wall_ms, llm_wall_ms,
                                          diff_bytes, diff_lines)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, 1, 1, "manual", "1", "manual", "manual", "PASS", "blob", 1, 0,
                "blob", now, 2, 3, 5, "engine_json", 100L, 90L, 10L, 1L);
    }
}
