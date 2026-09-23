package gate.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.mcp.McpToolDispatcher;
import gate.adapters.mcp.McpToolRegistry;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.testkit.GateHarness;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code ticket_edit} 工具：元信息可改、状态不可碰、作用域与建票同源。
 *
 * <p>核心安全属性有两条，都用「结构性不可达」而非「运行时拒绝」实现：工具 schema 里没有
 * stage 属性，{@code TicketRequestParser.MCP_EDIT_KEYS} 也不接受该键——改状态这条路在
 * 工具层面根本表达不出来。
 */
@Tag("slow")
class TicketEditToolTest {

    private GateHarness harness;
    private McpToolDispatcher dispatcher;
    private String agentToken;   // bound to T-1
    private String humanToken;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
        harness.createTicket("T-1");
        agentToken = harness.credentials().issueAgentToken("T-1", Instant.now());
        humanToken = harness.credentials().issueHumanToken(Instant.now());
        dispatcher = new McpToolDispatcher(
                harness.service(), harness.credentials(),
                harness.presubmits(), harness.reviewResults(),
                harness.blobStore(), harness.providerRepository(), harness.config(),
                harness.tickets(), harness.sessionRepository());
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void agent_edits_its_own_ticket_without_naming_it() {
        Map<String, Object> result = dispatcher.dispatch("ticket_edit",
                Map.of("title", "改过的标题", "note", "补充说明"), agentToken);

        assertEquals("T-1", result.get("ticket_no"));
        assertEquals("改过的标题", result.get("title"));
        assertEquals("补充说明", result.get("note"));
        Ticket stored = harness.ticket("T-1");
        assertEquals("改过的标题", stored.title());
        assertEquals("补充说明", stored.note());
    }

    @Test
    void omitted_fields_keep_their_stored_values() {
        dispatcher.dispatch("ticket_edit", Map.of(
                "title", "标题一", "description", "需求描述", "note", "备注", "priority", "P1",
                "labels", List.of("bug")), agentToken);

        dispatcher.dispatch("ticket_edit", Map.of("title", "标题二"), agentToken);

        Ticket t = harness.ticket("T-1");
        assertEquals("标题二", t.title());
        assertEquals("需求描述", t.description(), "an omitted field must not be blanked");
        assertEquals("备注", t.note());
        assertEquals("P1", t.priority());
        assertEquals(List.of("bug"), t.labels());
    }

    @Test
    void empty_values_clear_optional_fields_and_empty_labels_clear_the_list() {
        dispatcher.dispatch("ticket_edit", Map.of(
                "description", "需求描述", "note", "备注", "labels", List.of("bug")), agentToken);

        Map<String, Object> result = dispatcher.dispatch("ticket_edit", Map.of(
                "description", "", "labels", List.of()), agentToken);

        assertNull(result.get("description"));
        assertEquals(List.of(), result.get("labels"));
        assertEquals("备注", harness.ticket("T-1").note(),
                "clearing one optional field must not clear another");
    }

    @Test
    void priority_is_settable_and_clearable() {
        assertEquals("P0", dispatcher.dispatch("ticket_edit",
                Map.of("priority", "P0"), agentToken).get("priority"));
        assertNull(dispatcher.dispatch("ticket_edit",
                Map.of("priority", ""), agentToken).get("priority"));
        assertNull(harness.ticket("T-1").priority());
    }

    @Test
    void labels_are_replaced_as_a_whole_list() {
        dispatcher.dispatch("ticket_edit", Map.of("labels", List.of("a", "b")), agentToken);
        assertEquals(List.of("b", "c"), dispatcher.dispatch("ticket_edit",
                Map.of("labels", List.of("b", "c")), agentToken).get("labels"));
    }

    // ------------------------------------------------------------------
    // The stage is structurally out of reach
    // ------------------------------------------------------------------

    /**
     * Not merely refused: unrepresentable. A {@code stage} argument is an unknown field, so the
     * dispatch fails validation and the ticket does not move.
     */
    @Test
    void a_stage_argument_is_an_unknown_field_and_the_ticket_does_not_move() {
        var ex = assertThrows(gate.domain.error.GateValidationException.class,
                () -> dispatcher.dispatch("ticket_edit",
                        Map.of("stage", "DONE"), agentToken));
        assertTrue(ex.getMessage().contains("stage"), ex.getMessage());
        assertTrue(ex.getMessage().contains("unknown field"), ex.getMessage());
        assertEquals(TicketStage.IN_PROGRESS, harness.ticket("T-1").stage());
    }

    @Test
    void every_gate_controlled_key_is_rejected_with_the_same_account() {
        for (String key : List.of("stage", "project_id", "target_ref", "target_branch",
                "agent_config_id")) {
            assertThrows(gate.domain.error.GateValidationException.class,
                    () -> dispatcher.dispatch("ticket_edit", Map.of(key, "x"), agentToken),
                    key + " must not be settable through ticket_edit");
        }
        assertEquals(TicketStage.IN_PROGRESS, harness.ticket("T-1").stage());
        assertNull(harness.ticket("T-1").projectId());
    }

    @Test
    void the_tool_schema_declares_no_stage_property() {
        McpToolRegistry.ToolDef def = McpToolRegistry.find("ticket_edit").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) def.inputSchema().get("properties");
        assertFalse(properties.containsKey("stage"),
                "the schema must not offer a stage parameter at all: " + properties.keySet());
        for (String forbidden : List.of("project_id", "target_ref", "target_branch",
                "agent_config_id")) {
            assertFalse(properties.containsKey(forbidden),
                    "schema must not expose " + forbidden + ": " + properties.keySet());
        }
        assertTrue(properties.containsKey("title"));
        assertTrue(properties.containsKey("priority"));
        assertTrue(properties.containsKey("description"));
        assertTrue(properties.containsKey("note"));
        assertTrue(properties.containsKey("labels"));
    }

    /** Metadata edits never touch the stage, even on a terminal ticket the gate has closed out. */
    @Test
    void editing_metadata_leaves_a_terminal_stage_alone() {
        harness.tickets().updateStage("T-1", TicketStage.DONE, Instant.now());

        dispatcher.dispatch("ticket_edit", Map.of("title", "终态也能改标题"), agentToken);

        assertEquals("终态也能改标题", harness.ticket("T-1").title());
        assertEquals(TicketStage.DONE, harness.ticket("T-1").stage(),
                "an edit must never move the stage");
    }

    // ------------------------------------------------------------------
    // Scope and validation feedback
    // ------------------------------------------------------------------

    @Test
    void agent_cannot_edit_a_ticket_of_another_project() {
        harness.createProject("p1", "项目一");
        var foreign = harness.createProjectTicket("p1", "别的项目的工单");

        McpToolDispatcher.PermissionDeniedException ex = assertThrows(
                McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("ticket_edit",
                        Map.of("ticket_no", foreign.ticketNo(), "title", "越权"),
                        agentToken));
        assertTrue(ex.getMessage().contains("permission denied"), ex.getMessage());
        assertEquals("别的项目的工单", harness.ticket(foreign.ticketNo()).title());
    }

    /**
     * The dispatch-level binding check applies to {@code ticket_edit} exactly as it does to the
     * presubmit tools: an agent edits ITS OWN ticket and nothing else — not even a sibling ticket
     * in the same project (unlike {@code session_read}, which is project-scoped by design).
     */
    @Test
    void agent_cannot_edit_a_sibling_ticket_in_its_own_project() {
        harness.createProject("p1", "项目一");
        var mine = harness.createProjectTicket("p1", "我的工单");
        var sibling = harness.createProjectTicket("p1", "同项目另一张工单");
        String bound = harness.credentials().issueAgentToken(mine.ticketNo(), Instant.now());

        assertThrows(McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("ticket_edit",
                        Map.of("ticket_no", sibling.ticketNo(), "title", "改了隔壁的"), bound));
        assertEquals("同项目另一张工单", harness.ticket(sibling.ticketNo()).title());
        assertEquals(mine.ticketNo(),
                dispatcher.dispatch("ticket_edit", Map.of("title", "自己的可以改"), bound)
                        .get("ticket_no"));
    }

    @Test
    void human_token_edits_any_ticket_it_names() {
        harness.createProject("p1", "项目一");
        var other = harness.createProjectTicket("p1", "项目工单");

        Map<String, Object> result = dispatcher.dispatch("ticket_edit",
                Map.of("ticket_no", other.ticketNo(), "title", "人工改标题"), humanToken);

        assertEquals(other.ticketNo(), result.get("ticket_no"));
        assertEquals("人工改标题", harness.ticket(other.ticketNo()).title());
    }

    @Test
    void human_must_name_the_ticket_and_unknown_ones_are_domain_errors() {
        // Omitting the ticket is a request-shape problem → validation.
        McpToolDispatcher.ToolException missing = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("ticket_edit", Map.of("title", "x"), humanToken));
        assertTrue(missing.getMessage().contains("ticket_no"), missing.getMessage());

        // An unknown ticket is a state problem → domain (GateException; the MCP server maps
        // USAGE to INVALID_PARAMS with layer=domain, same as the other tools).
        gate.domain.error.GateException unknown = assertThrows(gate.domain.error.GateException.class,
                () -> dispatcher.dispatch("ticket_edit",
                        Map.of("ticket_no", "T-999", "title", "x"), humanToken));
        assertTrue(unknown.getMessage().contains("no such ticket: T-999"), unknown.getMessage());
    }

    @Test
    void an_edit_with_no_editable_field_is_a_domain_error() {
        gate.domain.error.GateException ex = assertThrows(gate.domain.error.GateException.class,
                () -> dispatcher.dispatch("ticket_edit", Map.of(), agentToken));
        assertTrue(ex.getMessage().contains("nothing to update"), ex.getMessage());
    }

    @Test
    void invalid_values_are_reported_per_field() {
        var ex = assertThrows(gate.domain.error.GateValidationException.class,
                () -> dispatcher.dispatch("ticket_edit",
                        Map.of("title", "  ", "priority", "P9"), agentToken));
        assertEquals(2, ex.fieldErrors().size(), ex.fieldErrors().toString());
        assertEquals("T-1", harness.ticket("T-1").title(), "a failed edit writes nothing");
    }

    @Test
    void null_valued_optional_fields_do_not_wipe_stored_values() {
        dispatcher.dispatch("ticket_edit", Map.of("note", "重要备注"), agentToken);

        // Models routinely fill optional schema properties with null; that must read as "omit".
        var raw = new java.util.LinkedHashMap<String, Object>();
        raw.put("title", "新标题");
        raw.put("note", null);
        dispatcher.dispatch("ticket_edit", raw, agentToken);

        assertEquals("重要备注", harness.ticket("T-1").note());
    }
}
