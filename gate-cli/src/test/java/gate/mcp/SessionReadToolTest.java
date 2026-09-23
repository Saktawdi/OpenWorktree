package gate.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.mcp.McpToolDispatcher;
import gate.domain.session.Role;
import gate.testkit.GateHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code session_read} 工具：域/项目作用域、参数反馈、只读性与真实落库往返。
 *
 * <p>作用域语义与 {@code ticket_create} 同源（同一 {@code agentProjectScope}）：agent 令牌能读
 * <b>自己项目</b>下的任意会话（不是只有自己那张工单），跨项目会话拒绝；human 令牌不受限。
 */
@Tag("slow")
class SessionReadToolTest {

    private GateHarness harness;
    private McpToolDispatcher dispatcher;
    private String agentToken;   // bound to T-1
    private String humanToken;
    private String sessionId;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
        harness.createTicket("T-1");
        agentToken = harness.credentials().issueAgentToken("T-1", java.time.Instant.now());
        humanToken = harness.credentials().issueHumanToken(java.time.Instant.now());
        dispatcher = new McpToolDispatcher(
                harness.service(), harness.credentials(),
                harness.presubmits(), harness.reviewResults(),
                harness.blobStore(), harness.providerRepository(), harness.config(),
                harness.tickets(), harness.sessionRepository());

        sessionId = "s-1";
        harness.createSession(sessionId, "T-1", "cfg-1");
        harness.appendMessage(sessionId, Role.USER, "把登录接口改成分页返回");
        harness.appendMessage(sessionId, Role.ASSISTANT, "已定位到 UserController#list");
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void agent_reads_a_session_of_its_own_ticket() {
        Map<String, Object> result = dispatcher.dispatch("session_read",
                Map.of("session_id", sessionId), agentToken);

        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) result.get("session");
        assertEquals(sessionId, session.get("id"));
        assertEquals("T-1", session.get("ticket_no"));
        assertEquals("ACTIVE", session.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals(2, messages.size());
        assertEquals("USER", messages.get(0).get("role"));
        assertEquals("把登录接口改成分页返回", messages.get(0).get("content"),
                "中文内容须经 blob 往返后无损");
        assertEquals(1L, messages.get(1).get("index"));
    }

    @Test
    void human_reads_any_session() {
        Map<String, Object> result = dispatcher.dispatch("session_read",
                Map.of("session_id", sessionId), humanToken);
        assertEquals(2, result.get("returned"));
    }

    /**
     * The scope is the project, not the ticket: a sibling ticket's session inside the same project
     * is readable — that is what makes the tool useful (an agent can look at what the neighbouring
     * session in this project already did).
     */
    @Test
    void agent_reads_a_sibling_ticket_session_in_the_same_project() {
        harness.createProject("p1", "项目一");
        var mine = harness.createProjectTicket("p1", "我的工单");
        var sibling = harness.createProjectTicket("p1", "同项目另一张工单");
        String projectAgentToken = harness.credentials()
                .issueAgentToken(mine.ticketNo(), java.time.Instant.now());
        harness.createSession("s-sibling", sibling.ticketNo(), "cfg-2");
        harness.appendMessage("s-sibling", Role.USER, "同项目会话");

        Map<String, Object> result = dispatcher.dispatch("session_read",
                Map.of("session_id", "s-sibling"), projectAgentToken);
        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) result.get("session");
        assertEquals("p1", session.get("project_id"));
        assertEquals(sibling.ticketNo(), session.get("ticket_no"));
    }

    @Test
    void agent_is_denied_a_session_of_another_project() {
        harness.createProject("p1", "项目一");
        harness.createProject("p2", "项目二");
        var mine = harness.createProjectTicket("p1", "我的工单");
        var foreign = harness.createProjectTicket("p2", "别的项目的工单");
        String p1AgentToken = harness.credentials()
                .issueAgentToken(mine.ticketNo(), java.time.Instant.now());
        harness.createSession("s-other", foreign.ticketNo(), "cfg-2");

        McpToolDispatcher.PermissionDeniedException ex = assertThrows(
                McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("session_read", Map.of("session_id", "s-other"),
                        p1AgentToken));
        assertTrue(ex.getMessage().contains("permission denied"), ex.getMessage());
        assertTrue(ex.getMessage().contains("s-other"), ex.getMessage());
    }

    @Test
    void agent_of_an_unaffiliated_ticket_is_denied_a_project_session() {
        harness.createProject("p1", "项目一");
        var owned = harness.createProjectTicket("p1", "接入项目的工单");
        harness.createSession("s-owned", owned.ticketNo(), "cfg-2");

        // agentToken is bound to T-1, which has no project — it cannot reach project sessions.
        assertThrows(McpToolDispatcher.PermissionDeniedException.class,
                () -> dispatcher.dispatch("session_read", Map.of("session_id", "s-owned"),
                        agentToken));
    }

    @Test
    void unknown_session_reports_a_domain_error_not_a_permission_error() {
        McpToolDispatcher.ToolException ex = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("session_read", Map.of("session_id", "nope"), humanToken));
        assertTrue(ex.getMessage().contains("no such session: nope"), ex.getMessage());
        assertNotNull(ex.data());
        assertEquals("domain", ex.data().get("layer"));
    }

    @Test
    void missing_session_id_reports_the_broken_field() {
        McpToolDispatcher.ToolException ex = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("session_read", Map.of(), humanToken));
        assertTrue(ex.getMessage().contains("session_id: missing required parameter"),
                ex.getMessage());
        assertEquals("validation", ex.data().get("layer"));
    }

    @Test
    void wrong_typed_arguments_report_their_fields() {
        McpToolDispatcher.ToolException typed = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("session_read",
                        Map.of("session_id", List.of("s-1")), humanToken));
        assertTrue(typed.getMessage().contains("wrong JSON type"), typed.getMessage());

        McpToolDispatcher.ToolException limit = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("session_read",
                        Map.of("session_id", sessionId, "limit", 0), humanToken));
        assertTrue(limit.getMessage().contains("limit"), limit.getMessage());

        McpToolDispatcher.ToolException before = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("session_read",
                        Map.of("session_id", sessionId, "before_index", -1), humanToken));
        assertTrue(before.getMessage().contains("before_index"), before.getMessage());
    }

    @Test
    void before_index_pages_towards_older_messages_over_the_real_store() {
        for (int i = 0; i < 8; i++) {
            harness.appendMessage(sessionId, Role.ASSISTANT, "第 " + i + " 条");
        }
        Map<String, Object> page1 = dispatcher.dispatch("session_read",
                Map.of("session_id", sessionId, "limit", 4), humanToken);
        assertEquals(10, page1.get("total_messages"));
        assertEquals(4, page1.get("returned"));
        assertEquals(6L, page1.get("first_index"));
        assertEquals(true, page1.get("has_more"));

        Map<String, Object> page2 = dispatcher.dispatch("session_read",
                Map.of("session_id", sessionId, "limit", 4,
                        "before_index", page1.get("next_before_index")), humanToken);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) page2.get("messages");
        assertEquals(4, messages.size());
        assertEquals(2L, messages.get(0).get("index"), "second page continues where the first stopped");
        assertEquals("第 0 条", messages.get(0).get("content"));
    }

    /** Read-only: the tool must not append, rewrite or re-status anything it reads. */
    @Test
    void reading_a_session_leaves_the_transcript_untouched() {
        int before = harness.sessionRepository().findMessages(sessionId).size();
        var statusBefore = harness.sessionRepository().find(sessionId).orElseThrow().status();

        dispatcher.dispatch("session_read", Map.of("session_id", sessionId), agentToken);
        dispatcher.dispatch("session_read", Map.of("session_id", sessionId, "limit", 1), humanToken);

        assertEquals(before, harness.sessionRepository().findMessages(sessionId).size(),
                "no message rows were added");
        assertEquals(statusBefore, harness.sessionRepository().find(sessionId).orElseThrow().status(),
                "session status untouched");
    }

    @Test
    void empty_session_returns_an_empty_window_with_metadata() {
        harness.createSession("s-empty", "T-1", "cfg-1");
        Map<String, Object> result = dispatcher.dispatch("session_read",
                Map.of("session_id", "s-empty"), humanToken);
        assertEquals(0, result.get("total_messages"));
        assertEquals(0, result.get("returned"));
        assertEquals(false, result.get("has_more"));
    }

    /**
     * The wire shape an agent actually sees: {@code tools/call} over the JSON-RPC loop returns the
     * transcript as a single {@code content[0].text} JSON string — this is also the UTF-8 path that
     * once turned 中文 tool results into mojibake, so the round trip asserts the text survives.
     */
    @Test
    void over_the_mcp_stdio_loop_the_transcript_arrives_as_json_text() throws Exception {
        gate.adapters.mcp.McpServer server = new gate.adapters.mcp.McpServer(dispatcher, humanToken);
        String input = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"session_read\",\"arguments\":{\"session_id\":\""
                + sessionId + "\"}}}\n";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        server.run(new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true,
                        java.nio.charset.StandardCharsets.UTF_8));

        Map<?, ?> response = (Map<?, ?>) gate.application.util.MiniJson.parse(
                out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(7L, response.get("id"));
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        java.util.List<?> content = (java.util.List<?>) result.get("content");
        Map<?, ?> item = (Map<?, ?>) content.get(0);
        assertEquals("text", item.get("type"));

        Map<?, ?> payload = (Map<?, ?>) gate.application.util.MiniJson.parse(String.valueOf(item.get("text")));
        assertEquals(2L, payload.get("total_messages"));
        Map<?, ?> session = (Map<?, ?>) payload.get("session");
        assertEquals(sessionId, session.get("id"));
        java.util.List<?> messages = (java.util.List<?>) payload.get("messages");
        assertEquals("把登录接口改成分页返回",
                ((Map<?, ?>) messages.get(0)).get("content"), "中文须经 stdio 往返无损");
    }
}
