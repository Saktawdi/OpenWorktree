package gate.mcp;

import gate.adapters.mcp.McpToolDispatcher;
import gate.adapters.mcp.McpToolRegistry;
import gate.application.util.MiniJson;
import gate.ports.store.CredentialRepository.Domain;
import gate.testkit.GateHarness;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Permission domain tests for the MCP server (架构落地执行文档 §5.4, §11.3, 执行文档 §4 P3 A9).
 *
 * <p><b>List-driven</b> (§5.4 hard constraint): the test traverses {@link McpToolRegistry#humanDomainTools()}
 * with agent-domain credentials and asserts each call is denied. When a new tool is added to the
 * registry, this test automatically covers it — there is no separate registration to forget.
 *
 * <p>The server-side domain check is the <b>authoritative</b> defence (§1.3: CLI flags like
 * {@code --allowedTools} are agent-controllable config and cannot be the sole defence).
 * {@code review_run} and {@code commit_and_publish} must <b>never</b> be reachable from the agent
 * domain — otherwise an agent could repeatedly run reviews until it lucks into a pass.
 */
class PermissionDomainTest {

    private GateHarness harness;
    private McpToolDispatcher dispatcher;
    private String agentToken;
    private String humanToken;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
        harness.createTicket("T-1");
        // Issue an agent-domain token bound to T-1.
        agentToken = harness.credentials().issueAgentToken("T-1", java.time.Instant.now());
        // Issue a human-domain token.
        humanToken = harness.credentials().issueHumanToken(java.time.Instant.now());

        dispatcher = new McpToolDispatcher(
                harness.service(), harness.credentials(),
                harness.presubmits(), harness.reviewResults(),
                harness.blobStore(), harness.providerRepository(), harness.config());
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    // ------------------------------------------------------------------
    // A9 core: agent-domain credentials calling ANY human-domain tool → denied
    // ------------------------------------------------------------------

    /**
     * The core A9 assertion: agent-domain credentials traversing ALL human-domain tools, each
     * denied. List-driven — new tools are covered automatically.
     */
    @Test
    void agent_credentials_cannot_call_any_human_domain_tool() {
        assertFalse(McpToolRegistry.humanDomainTools().isEmpty(),
                "test fixture: there must be human-domain tools to test");

        for (String tool : McpToolRegistry.humanDomainTools()) {
            Map<String, Object> args = defaultArgsFor(tool);
            McpToolDispatcher.PermissionDeniedException ex = assertThrows(
                    McpToolDispatcher.PermissionDeniedException.class,
                    () -> dispatcher.dispatch(tool, args, agentToken),
                    "agent-domain token MUST be denied for human-domain tool: " + tool);
            assertTrue(ex.getMessage().contains("permission denied"),
                    "error message must say 'permission denied' for tool: " + tool);
        }
    }

    // ------------------------------------------------------------------
    // Reverse: agent-domain credentials CAN reach agent-domain tools (passes domain check)
    // ------------------------------------------------------------------

    /**
     * Agent-domain credentials should pass the domain check for agent-domain tools. The call may
     * still fail for business reasons (e.g. empty diff), but it must NOT be a PermissionDeniedException.
     */
    @Test
    void agent_credentials_can_reach_agent_domain_tools() {
        assertFalse(McpToolRegistry.agentDomainTools().isEmpty(),
                "test fixture: there must be agent-domain tools to test");

        for (String tool : McpToolRegistry.agentDomainTools()) {
            Map<String, Object> args = defaultArgsFor(tool);
            try {
                dispatcher.dispatch(tool, args, agentToken);
                // May succeed or throw a business error — either is fine for domain check purposes.
            } catch (McpToolDispatcher.PermissionDeniedException e) {
                fail("agent-domain token must NOT be denied for agent-domain tool: " + tool
                        + " — got: " + e.getMessage());
            } catch (McpToolDispatcher.ToolException e) {
                // Business-level error (e.g. "no presubmit round") — expected, domain check passed.
            } catch (Exception e) {
                // Other business errors are acceptable — the domain check passed.
            }
        }
    }

    // ------------------------------------------------------------------
    // No token / invalid token → all tools denied
    // ------------------------------------------------------------------

    @Test
    void no_token_denies_all_tools() {
        for (String tool : McpToolRegistry.all().stream().map(McpToolRegistry.ToolDef::name).toList()) {
            Map<String, Object> args = defaultArgsFor(tool);
            // No token (null) → domain is INVALID → denied
            assertThrows(McpToolDispatcher.PermissionDeniedException.class,
                    () -> dispatcher.dispatch(tool, args, null),
                    "null token must be denied for tool: " + tool);
        }
    }

    @Test
    void invalid_token_denies_all_tools() {
        String bogus = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        for (String tool : McpToolRegistry.all().stream().map(McpToolRegistry.ToolDef::name).toList()) {
            Map<String, Object> args = defaultArgsFor(tool);
            assertThrows(McpToolDispatcher.PermissionDeniedException.class,
                    () -> dispatcher.dispatch(tool, args, bogus),
                    "invalid token must be denied for tool: " + tool);
        }
    }

    // ------------------------------------------------------------------
    // Human domain can reach everything (including agent-domain tools)
    // ------------------------------------------------------------------

    @Test
    void human_token_can_reach_human_domain_tools_domain_check() {
        for (String tool : McpToolRegistry.humanDomainTools()) {
            Map<String, Object> args = defaultArgsFor(tool);
            try {
                dispatcher.dispatch(tool, args, humanToken);
            } catch (McpToolDispatcher.PermissionDeniedException e) {
                fail("human-domain token must NOT be denied for human-domain tool: " + tool);
            } catch (Exception e) {
                // Business errors are fine — the domain check passed.
            }
        }
    }

    // ------------------------------------------------------------------
    // Agent token is bound to its ticket — cannot operate on other tickets
    // ------------------------------------------------------------------

    @Test
    void agent_token_bound_to_wrong_ticket_is_denied() {
        harness.createTicket("T-2");
        // Agent token is bound to T-1, but we try to operate on T-2.
        Map<String, Object> args = Map.of("ticket_no", "T-2");
        assertThrows(McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("presubmit_create", args, agentToken),
                "agent token bound to T-1 must be denied for T-2");
    }

    @Test
    void revoked_token_is_invalid() {
        harness.credentials().revoke(agentToken);
        Domain d = harness.credentials().validate(agentToken);
        assertFalse(d.isValid(), "revoked token must be invalid");
    }

    // ------------------------------------------------------------------
    // Registry invariants
    // ------------------------------------------------------------------

    @Test
    void review_run_and_publish_never_in_agent_domain() {
        // The two tools that must NEVER be in the agent domain (执行文档 §4 P3).
        assertFalse(McpToolRegistry.agentDomainTools().contains("review_run"),
                "review_run MUST NOT be in agent domain");
        assertFalse(McpToolRegistry.agentDomainTools().contains("commit_and_publish"),
                "commit_and_publish MUST NOT be in agent domain");
        assertTrue(McpToolRegistry.humanDomainTools().contains("review_run"),
                "review_run must be in human domain");
        assertTrue(McpToolRegistry.humanDomainTools().contains("commit_and_publish"),
                "commit_and_publish must be in human domain");
    }

    @Test
    void every_tool_is_in_exactly_one_domain() {
        for (McpToolRegistry.ToolDef tool : McpToolRegistry.all()) {
            boolean inAgent = McpToolRegistry.agentDomainTools().contains(tool.name());
            boolean inHuman = McpToolRegistry.humanDomainTools().contains(tool.name());
            assertTrue(inAgent || inHuman,
                    "tool " + tool.name() + " is in no domain");
            assertFalse(inAgent && inHuman,
                    "tool " + tool.name() + " is in both domains");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Provides minimal valid args for each tool so the domain check is the only thing tested. */
    private static Map<String, Object> defaultArgsFor(String tool) {
        return switch (tool) {
            case "presubmit_create" -> Map.of("ticket_no", "T-1");
            case "presubmit_get_diff" -> Map.of("ticket_no", "T-1");
            case "review_result_get" -> Map.of("ticket_no", "T-1");
            case "review_run" -> Map.of("ticket_no", "T-1");
            case "commit_and_publish" -> Map.of("ticket_no", "T-1");
            case "config_show" -> Map.of();
            case "provider_list" -> Map.of();
            default -> Map.of();
        };
    }
}
