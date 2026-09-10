package gate.mcp;

import gate.adapters.mcp.McpServer;
import gate.adapters.mcp.McpToolDispatcher;
import gate.testkit.GateHarness;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP protocol compliance: initialize / tools/list / tools/call over JSON-RPC 2.0 on stdio
 * (spike-结论 §3, 架构落地执行文档 §5.4).
 *
 * <p>Drives the {@link McpServer} through in-memory pipes (no real subprocess), sending one
 * JSON-RPC message per line and reading responses from stdout.
 */
@Tag("slow")
class McpProtocolTest {

    private GateHarness harness;
    private McpServer server;
    private String humanToken;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
        harness.createTicket("T-1");
        humanToken = harness.credentials().issueHumanToken(java.time.Instant.now());

        McpToolDispatcher dispatcher = new McpToolDispatcher(
                harness.service(), harness.credentials(),
                harness.presubmits(), harness.reviewResults(),
                harness.blobStore(), harness.providerRepository(), harness.config(),
                harness.tickets());
        server = new McpServer(dispatcher, humanToken);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void initialize_returns_capabilities_and_server_info() throws Exception {
        String resp = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertTrue(resp.contains("\"protocolVersion\""), "must have protocolVersion");
        assertTrue(resp.contains("\"capabilities\""), "must have capabilities");
        assertTrue(resp.contains("\"serverInfo\""), "must have serverInfo");
        assertTrue(resp.contains("gate-mcp"), "server name must be gate-mcp");
    }

    @Test
    void tools_list_returns_all_registered_tools() throws Exception {
        String resp = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertTrue(resp.contains("ticket_create"), "must list ticket_create");
        assertTrue(resp.contains("presubmit_create"), "must list presubmit_create");
        assertTrue(resp.contains("presubmit_get_diff"), "must list presubmit_get_diff");
        assertTrue(resp.contains("review_result_get"), "must list review_result_get");
        assertTrue(resp.contains("sync_base"), "must list sync_base");
        assertTrue(resp.contains("review_run"), "must list review_run");
        assertTrue(resp.contains("commit_and_publish"), "must list commit_and_publish");
        assertTrue(resp.contains("config_show"), "must list config_show");
        assertTrue(resp.contains("provider_list"), "must list provider_list");
        assertTrue(resp.contains("\"inputSchema\""), "tools must have inputSchema");
    }

    @Test
    void tools_list_tools_have_descriptions() throws Exception {
        String resp = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertTrue(resp.contains("\"description\""), "tools must have descriptions");
    }

    @Test
    void notifications_initialized_gets_no_response() throws Exception {
        // A notification (no id) should not get a response.
        String input = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
        String resp = sendRaw(input);
        assertEquals("", resp.trim(), "notifications must not get a response");
    }

    @Test
    void unknown_method_returns_method_not_found() throws Exception {
        String resp = send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"nonexistent/method\"}");
        assertTrue(resp.contains("\"error\""), "unknown method must return error");
        assertTrue(resp.contains("-32601"), "must be METHOD_NOT_FOUND (-32601)");
    }

    @Test
    void parse_error_returns_parse_error() {
        String resp = sendRaw("not valid json\n");
        assertTrue(resp.contains("\"error\""), "parse error must return error");
        assertTrue(resp.contains("-32700"), "must be PARSE_ERROR (-32700)");
    }

    @Test
    void blank_lines_are_ignored() {
        String resp = sendRaw("\n\n\n");
        assertEquals("", resp.trim(), "blank lines must produce no output");
    }

    @Test
    void config_show_via_mcp_returns_config() throws Exception {
        String resp = send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"config_show\",\"arguments\":{}}}");
        assertTrue(resp.contains("\"result\""), "must return result");
        assertTrue(resp.contains("auth_repo"), "must contain auth_repo in config");
        assertTrue(resp.contains("target_ref_whitelist"), "must contain target_ref_whitelist");
    }

    // --- helpers ---

    private String send(String jsonRpcLine) {
        return sendRaw(jsonRpcLine + "\n");
    }

    private String sendRaw(String input) {
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        server.run(in, ps, err);
        return out.toString(StandardCharsets.UTF_8);
    }
}
