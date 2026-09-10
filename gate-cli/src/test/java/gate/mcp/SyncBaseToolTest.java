package gate.mcp;

import gate.adapters.mcp.McpToolDispatcher;
import gate.domain.error.GateException;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.testkit.GateHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code sync_base} MCP 工具（T-118 基座同步暴露给 agent）：agent 域令牌把落后的工单克隆快进到
 * 主分支 tip，未提交改动按 {@code allow_dirty} 语义跳过或 stash 重放；工单绑定校验、
 * 快速模式超级工单拒绝与终态/审查期守卫沿用 BaseSyncHandler 的语义。
 */
@Tag("slow")
class SyncBaseToolTest {

    private GateHarness harness;
    private McpToolDispatcher dispatcher;
    private String agentToken;
    private String humanToken;

    @BeforeEach
    void setUp() {
        // wireBaseSyncer=true: 按生产（GateRuntime）接线 CloneBaseSyncer，否则 syncBase 未装配。
        harness = new GateHarness("git", true);
        harness.createTicket("T-1");
        agentToken = harness.credentials().issueAgentToken("T-1", Instant.now());
        humanToken = harness.credentials().issueHumanToken(Instant.now());
        dispatcher = new McpToolDispatcher(
                harness.service(), harness.credentials(),
                harness.presubmits(), harness.reviewResults(),
                harness.blobStore(), harness.providerRepository(), harness.config(),
                harness.tickets());
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void agent_syncs_behind_clone_onto_base_tip() {
        advanceMain(2);

        Map<String, Object> result = dispatcher.dispatch("sync_base", Map.of("ticket_no", "T-1"), agentToken);

        assertEquals("T-1", result.get("ticket_no"));
        assertEquals("synced", result.get("status"));
        assertEquals(2, result.get("behind"));
        // 直插工单行的 targetRef 即主分支（target == base）：权威分支无需移动；
        // branch_moved=true 的生产拓扑（工单分支 ≠ 基分支）由 BaseSyncApiTest 覆盖。
        assertEquals(false, result.get("branch_moved"));
        assertEquals(tip("refs/heads/main"), tip("HEAD"), "clone HEAD must sit on the base tip");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dirty_clone_is_skipped_when_not_allowed_and_replayed_when_allowed() throws Exception {
        advanceMain(1);
        Path clone = harness.config().clonesRoot().resolve("T-1");
        Files.writeString(clone.resolve("wip.txt"), "half-done agent work\n");

        Map<String, Object> refused = dispatcher.dispatch("sync_base",
                Map.of("ticket_no", "T-1", "allow_dirty", false), agentToken);
        assertEquals("skipped", refused.get("status"));
        assertTrue(String.valueOf(refused.get("skipped_reason")).contains("未提交改动"));
        assertEquals("half-done agent work\n", Files.readString(clone.resolve("wip.txt")),
                "skip must leave the worktree untouched");

        Map<String, Object> synced = dispatcher.dispatch("sync_base",
                Map.of("ticket_no", "T-1", "allow_dirty", "true"), agentToken);
        assertEquals("synced", synced.get("status"));
        assertEquals(1, synced.get("behind"));
        assertEquals("half-done agent work\n", Files.readString(clone.resolve("wip.txt")),
                "uncommitted work must survive the sync");
        assertEquals(tip("refs/heads/main"), tip("HEAD"));
    }

    @Test
    void up_to_date_clone_is_a_reported_noop() {
        Map<String, Object> result = dispatcher.dispatch("sync_base", Map.of("ticket_no", "T-1"), agentToken);
        assertEquals("up_to_date", result.get("status"));
        assertEquals(0, result.get("behind"));
    }

    @Test
    void agent_token_cannot_sync_another_ticket() {
        harness.createTicket("T-2");
        Map<String, Object> args = Map.of("ticket_no", "T-2");
        McpToolDispatcher.PermissionDeniedException ex = assertThrows(
                McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("sync_base", args, agentToken),
                "agent token bound to T-1 must be denied for T-2");
        assertTrue(ex.getMessage().contains("ticket-bound"), ex.getMessage());
    }

    @Test
    void human_token_can_call_the_agent_tool() {
        Map<String, Object> result = dispatcher.dispatch("sync_base", Map.of("ticket_no", "T-1"), humanToken);
        assertEquals("up_to_date", result.get("status"));
    }

    @Test
    void quick_mode_super_ticket_is_refused() {
        Instant now = Instant.now();
        harness.tickets().insert(new Ticket("T-SUPER", "快速模式", harness.targetRef(),
                harness.root().resolve("ws").toString(),
                null, null, "manual", "human",
                TicketStage.IN_PROGRESS, now, now,
                null, null, null, null, null, null, null, List.of(), true));

        GateException ex = assertThrows(GateException.class,
                () -> dispatcher.dispatch("sync_base", Map.of("ticket_no", "T-SUPER"), humanToken));
        assertTrue(ex.getMessage().contains("super ticket"), ex.getMessage());
    }

    @Test
    void allow_dirty_wrong_type_is_a_validation_error() {
        McpToolDispatcher.ToolException ex = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("sync_base",
                        Map.of("ticket_no", "T-1", "allow_dirty", "sometimes"), agentToken));
        assertTrue(ex.getMessage().contains("allow_dirty"), ex.getMessage());
    }

    /** Adds {@code n} empty commits to the authoritative main branch (fetch, not push: the
     *  pre-receive hook would reject a plain push). */
    private void advanceMain(int n) {
        var cfg = harness.config();
        Path work = harness.root().resolve("main-advance-" + n + "-" + System.nanoTime());
        harness.gitCli().must(gate.domain.git.RepoRef.of(harness.root()), "clone", "--quiet",
                cfg.authRepo().toString(), work.toString());
        Map<String, String> env = Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost");
        for (int i = 0; i < n; i++) {
            harness.gitCli().must(work, env, "commit", "--allow-empty", "-m", "advance " + i);
        }
        harness.gitCli().must(gate.domain.git.RepoRef.of(cfg.authRepo()), "fetch", work.toAbsolutePath().toString(),
                "+refs/heads/main:refs/heads/main");
    }

    private String tip(String ref) {
        return harness.gitCli().line(gate.domain.git.RepoRef.of(harness.config().clonesRoot().resolve("T-1")),
                "rev-parse", ref).trim();
    }
}
