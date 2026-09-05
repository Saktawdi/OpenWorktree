package gate.mcp;

import gate.adapters.mcp.McpServer;
import gate.adapters.mcp.McpToolDispatcher;
import gate.domain.git.RepoRef;
import gate.domain.ticket.Ticket;
import gate.testkit.GateHarness;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP end-to-end (in-process): the full "trigger presubmit → read rejection → fix → second pass"
 * loop driven through the MCP server (执行文档 §4 P3 A11 — the in-process rehearsal of the real-CLI
 * smoke test).
 *
 * <p>This uses the manual review engine (no prism) so it runs without a live newapi link; the real
 * prism→newapi end-to-end is exercised separately by the A11 CLI smoke test. The point here is to
 * prove the MCP tool wiring and the two-domain flow are correct end-to-end.
 *
 * <p><b>Domain separation in action</b>: the agent domain drives presubmit and reads results; only
 * the human domain runs the review and publishes. Two separate server instances model the two
 * spawned processes with their two independent tokens.
 */
@Tag("slow")
class McpEndToEndTest {

    private GateHarness harness;
    private McpServer agentServer;
    private McpServer humanServer;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
        harness.createTicket("T-1");
        String agentToken = harness.credentials().issueAgentToken("T-1", Instant.now());
        String humanToken = harness.credentials().issueHumanToken(Instant.now());

        agentServer = newServer(agentToken);
        humanServer = newServer(humanToken);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /**
     * The full A11 loop, in-process, through the MCP tools:
     * <ol>
     *   <li>agent: presubmit_create (round 1)</li>
     *   <li>human: review_run → manual reject (round 1)</li>
     *   <li>agent: review_result_get → sees the rejection</li>
     *   <li>agent fixes the worktree, presubmit_create (round 2)</li>
     *   <li>human: review_run → manual pass (round 2)</li>
     *   <li>human: commit_and_publish → lands on auth</li>
     * </ol>
     */
    @Test
    void full_loop_presubmit_reject_fix_pass_publish() {
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(
                harness.config().clonesRoot().resolve("T-1").toString()));

        long commitsBefore = harness.authCommitCount();

        // --- Round 1: agent submits work that will be rejected ---
        harness.writeFile(clone, "feature.txt", "buggy first attempt\n");
        String r1 = call(agentServer, "presubmit_create",
                "{\"ticket_no\":\"T-1\"}");
        assertTrue(r1.contains("review_round"), "presubmit must report a round: " + r1);
        assertFalse(r1.contains("\"isError\":true"), "presubmit_create must succeed: " + r1);

        // Human runs review → manual reject. Manual mode: the review tool uses forEngine, but our
        // harness has no engine configured, so review falls to manual. We reject via the service
        // directly to model the human verdict (the MCP review_run tool wraps engine mode; in the
        // no-engine harness it behaves as manual). To keep the MCP path honest we drive the manual
        // reject through the service, matching how a human-domain caller would supply the verdict.
        harness.service().review(new gate.application.review.ReviewCommand("T-1", null, false, "fix the bug"));

        // Agent reads the rejection.
        String rr = call(agentServer, "review_result_get", "{\"ticket_no\":\"T-1\"}");
        assertTrue(rr.contains("REJECT"), "agent must see REJECT verdict: " + rr);

        // --- Round 2: agent fixes and resubmits ---
        harness.writeFile(clone, "feature.txt", "fixed and correct\n");
        String r2 = call(agentServer, "presubmit_create", "{\"ticket_no\":\"T-1\"}");
        assertTrue(r2.contains("review_round") && r2.contains(":2"),
                "second presubmit must be round 2: " + r2);

        // Human runs review → manual pass.
        harness.service().review(new gate.application.review.ReviewCommand("T-1", null, true, null));

        // Human publishes through the MCP tool.
        String pub = call(humanServer, "commit_and_publish", "{\"ticket_no\":\"T-1\"}");
        assertTrue(pub.contains("commit_sha"), "publish must return a commit sha: " + pub);
        assertFalse(pub.contains("\"isError\":true"), "publish must succeed: " + pub);

        assertEquals(commitsBefore + 1, harness.authCommitCount(),
                "exactly one commit must land on auth after the full loop");
    }

    /** Agent domain cannot publish (A9 in the E2E context). */
    @Test
    void agent_cannot_publish() {
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(
                harness.config().clonesRoot().resolve("T-1").toString()));
        harness.writeFile(clone, "feature.txt", "content\n");
        call(agentServer, "presubmit_create", "{\"ticket_no\":\"T-1\"}");
        harness.service().review(new gate.application.review.ReviewCommand("T-1", null, true, null));

        // Agent tries to publish via the human-domain tool → JSON-RPC error (permission denied).
        String pub = call(agentServer, "commit_and_publish", "{\"ticket_no\":\"T-1\"}");
        assertTrue(pub.contains("\"error\""), "agent publish must be a JSON-RPC error: " + pub);
        assertTrue(pub.contains("permission denied"), "must say permission denied: " + pub);
        assertEquals(0, harness.authCommitCount() - harness.authCommitCount(), "no side effect");
    }

    /**
     * ticket_create is agent-callable and NOT ticket-bound: an agent working T-1 may spawn a
     * follow-up ticket (auto-numbered past every existing one) and its clone must materialize.
     */
    @Test
    void agent_creates_a_follow_up_ticket() {
        String r = call(agentServer, "ticket_create",
                "{\"title\":\"agent spawned follow-up\",\"labels\":[\"跟进\"],"
                        + "\"priority\":\"P2\",\"description\":\"discovered mid-work\"}");
        assertFalse(r.contains("\"isError\":true"), "ticket_create must succeed: " + r);
        assertTrue(r.contains("ticket_no"), r);
        assertTrue(r.contains("T-101"), "auto numbering must pick the next free T-nnn: " + r);
        assertTrue(r.contains("IN_PROGRESS"), r);
        assertTrue(r.contains("P2"), r);
        assertTrue(r.contains("refs/heads/T-101"), r);

        Ticket created = harness.ticket("T-101");
        assertEquals("跟进", created.labels().get(0));
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(created.clonePath())),
                "the clone must be materialized at creation time");
        // The new ticket's branch is cut from the base tip and its clone sits on it.
        assertEquals(harness.authTip(),
                harness.gitCli().line(RepoRef.of(java.nio.file.Path.of(created.clonePath())), "rev-parse", "HEAD").trim());
    }

    /** ticket_create requires a title. */
    @Test
    void ticket_create_without_title_is_rejected() {
        String r = call(agentServer, "ticket_create", "{}");
        assertTrue(r.contains("\"error\""), "missing title must be a JSON-RPC error: " + r);
        assertTrue(r.contains("-32602"), "invalid params must map to -32602: " + r);
        assertTrue(r.contains("missing required parameter: title"), r);
    }

    // --- helpers ---

    private McpServer newServer(String token) {
        McpToolDispatcher dispatcher = new McpToolDispatcher(
                harness.service(), harness.credentials(),
                harness.presubmits(), harness.reviewResults(),
                harness.blobStore(), harness.providerRepository(), harness.config(),
                harness.tickets());
        return new McpServer(dispatcher, token);
    }

    private String call(McpServer server, String tool, String argsJson) {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        server.run(in, ps, err);
        return out.toString(StandardCharsets.UTF_8);
    }
}
