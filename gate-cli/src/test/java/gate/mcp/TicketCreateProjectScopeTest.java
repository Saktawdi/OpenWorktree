package gate.mcp;

import gate.adapters.mcp.McpToolDispatcher;
import gate.domain.ticket.Ticket;
import gate.testkit.GateHarness;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ticket_create} project scoping (T-130 dispatch incident): an agent-domain token is bound
 * to one ticket, and the tickets it creates must stay inside that ticket's project — no
 * cross-project creation, no silently unprojected (gate-level) creation. Server-side and
 * authoritative, like every other domain check (§1.3).
 */
class TicketCreateProjectScopeTest {

    private GateHarness harness;
    private McpToolDispatcher dispatcher;
    /** Agent token of a ticket that lives in project proj-a (the dispatching session's token). */
    private String projectAgentToken;
    /** Agent token of an unaffiliated (gate-level) ticket. */
    private String unaffiliatedAgentToken;
    private String humanToken;
    private String hostTicketNo;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
        harness.createProject("proj-a", "项目A");
        harness.createProject("proj-b", "项目B");
        Ticket host = harness.createProjectTicket("proj-a", "宿主工单");
        hostTicketNo = host.ticketNo();
        harness.createTicket("T-1"); // unaffiliated gate-level ticket

        projectAgentToken = harness.credentials().issueAgentToken(hostTicketNo, Instant.now());
        unaffiliatedAgentToken = harness.credentials().issueAgentToken("T-1", Instant.now());
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

    // ------------------------------------------------------------------
    // Omitted project_id inherits the bound ticket's project (the T-130 case: the agent never
    // passed project_id, so its 10 follow-ups fell to the gate-level default repo).
    // ------------------------------------------------------------------

    @Test
    void omitted_project_id_inherits_the_bound_tickets_project() {
        Map<String, Object> out = dispatchCreate(projectAgentToken, Map.of("title", "跟进一"));
        assertEquals("proj-a", out.get("project_id"),
                "omitted project_id must inherit the bound ticket's project: " + out);
        assertEquals("proj-a", harness.ticket((String) out.get("ticket_no")).projectId());
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of((String) out.get("clone_path"))),
                "the clone must be materialized from the project's own auth repo");
    }

    @Test
    void blank_project_id_counts_as_omitted() {
        Map<String, Object> out = dispatchCreate(projectAgentToken, Map.of("title", "跟进二", "project_id", "  "));
        assertEquals("proj-a", out.get("project_id"), out.toString());
    }

    @Test
    void explicit_own_project_id_is_accepted() {
        Map<String, Object> out = dispatchCreate(projectAgentToken,
                Map.of("title", "跟进三", "project_id", "proj-a"));
        assertEquals("proj-a", out.get("project_id"), out.toString());
    }

    // ------------------------------------------------------------------
    // Cross-project creation is denied.
    // ------------------------------------------------------------------

    @Test
    void other_project_is_denied() {
        int before = harness.tickets().findAll().size();
        McpToolDispatcher.PermissionDeniedException ex = assertThrows(
                McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("title", "越界工单", "project_id", "proj-b"), projectAgentToken),
                "an agent of proj-a must not create tickets in proj-b");
        assertTrue(ex.getMessage().contains("cannot create a ticket in project proj-b"), ex.getMessage());
        assertEquals(before, harness.tickets().findAll().size(),
                "a denied call must leave no ticket behind");
    }

    @Test
    void agent_of_an_unaffiliated_ticket_cannot_create_in_any_project() {
        assertThrows(McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("title", "越界工单", "project_id", "proj-a"), unaffiliatedAgentToken),
                "a gate-level agent must not create project tickets");
    }

    @Test
    void unaffiliated_agent_stays_unaffiliated_when_project_id_omitted() {
        Map<String, Object> out = dispatchCreate(unaffiliatedAgentToken, Map.of("title", "门禁级跟进"));
        assertNull(out.get("project_id"), out.toString());
        assertNull(harness.ticket((String) out.get("ticket_no")).projectId());
    }

    // ------------------------------------------------------------------
    // Fail-closed scope resolution.
    // ------------------------------------------------------------------

    @Test
    void token_bound_to_a_missing_ticket_is_denied() {
        // issueAgentToken does not verify the ticket exists; a stale binding must fail closed
        // rather than fall back to an unrestricted project.
        String staleToken = harness.credentials().issueAgentToken("T-999", Instant.now());
        McpToolDispatcher.PermissionDeniedException ex = assertThrows(
                McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("ticket_create", Map.of("title", "孤儿令牌"), staleToken),
                "an unresolvable binding must not create any ticket");
        assertTrue(ex.getMessage().contains("cannot be determined"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Human/orchestrator tokens stay unrestricted (ops scripts may create anywhere).
    // ------------------------------------------------------------------

    @Test
    void human_token_may_create_in_any_project() {
        assertEquals("proj-b", dispatchCreate(humanToken,
                Map.of("title", "人工工单", "project_id", "proj-b")).get("project_id"));
        assertNull(dispatchCreate(humanToken, Map.of("title", "仓库级工单")).get("project_id"));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchCreate(String token, Map<String, Object> args) {
        return (Map<String, Object>) dispatcher.dispatch("ticket_create", args, token);
    }
}
