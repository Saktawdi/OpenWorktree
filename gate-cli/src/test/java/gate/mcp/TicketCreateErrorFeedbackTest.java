package gate.mcp;

import gate.adapters.io.AdapterLog;
import gate.adapters.mcp.McpServer;
import gate.adapters.mcp.McpToolDispatcher;
import gate.domain.error.FieldError;
import gate.domain.error.GateException;
import gate.domain.error.GateValidationException;
import gate.domain.project.Project;
import gate.testkit.GateHarness;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T-108: failed {@code ticket_create} calls give structured, field-level feedback and leave a
 * trace in the gate's own log.
 *
 * <p>Three contracts are exercised:
 * <ol>
 *   <li><b>Validation errors name every broken field</b> — missing title, wrong JSON types,
 *       out-of-range priority/stage/labels and unknown MCP fields are aggregated into one
 *       {@link GateValidationException} whose message and {@code error.data.fields} list the field,
 *       the problem, the expected format and the actual value.</li>
 *   <li><b>Validation and domain failures stay distinguishable</b> — duplicate ticket_no,
 *       unknown project and a missing auth repo surface as plain {@link GateException}(USAGE)
 *       carrying their context (the actual repo path), never as a parameter problem, and the MCP
 *       error response reports {@code layer=validation} vs {@code layer=domain}.</li>
 *   <li><b>Every failed MCP call is logged</b> — {@code McpServer} records the failure (tool,
 *       layer, reason, argument summary) into the {@link AdapterLog} file, i.e.
 *       {@code <gate-home>/adapters.log}, for post-hoc audit.</li>
 * </ol>
 */
@Tag("slow")
class TicketCreateErrorFeedbackTest {

    private GateHarness harness;
    private McpToolDispatcher dispatcher;
    private AdapterLog log;
    private McpServer server;
    private String agentToken;
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
                harness.tickets());
        log = AdapterLog.at(harness.config().gateHome().resolve("adapters.log"));
        server = new McpServer(dispatcher, humanToken, log);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    // ------------------------------------------------------------------
    // 1) Structured, aggregated, field-level validation errors
    // ------------------------------------------------------------------

    @Test
    void missing_title_is_a_validation_error_naming_the_field() {
        GateValidationException ex = assertThrows(GateValidationException.class,
                () -> dispatcher.dispatch("ticket_create", Map.of(), agentToken));
        assertTrue(ex.getMessage().contains("title"), ex.getMessage());
        assertTrue(ex.getMessage().contains("missing required parameter"), ex.getMessage());
        assertTrue(ex.getMessage().contains("non-blank string"), ex.getMessage());
        assertEquals(1, ex.fieldErrors().size());
        assertEquals("title", ex.fieldErrors().get(0).field());
    }

    @Test
    void all_broken_fields_are_reported_in_one_error() {
        // Missing title + out-of-range priority + wrong-typed description + unknown MCP-only field.
        GateValidationException ex = assertThrows(GateValidationException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("priority", "P9", "description", List.of("not", "a", "string"),
                                "labels", "nope", "stage", "DONE"), agentToken));
        Set<String> fields = ex.fieldErrors().stream().map(FieldError::field).collect(Collectors.toSet());
        assertTrue(fields.contains("title"), "title must be reported: " + fields);
        assertTrue(fields.contains("priority"), "priority must be reported: " + fields);
        assertTrue(fields.contains("description"), "description must be reported: " + fields);
        assertTrue(fields.contains("labels"), "labels must be reported: " + fields);
        assertTrue(fields.contains("stage"), "stage must be reported: " + fields);
        assertTrue(ex.getMessage().contains("P9"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[P0, P1, P2, P3]"), ex.getMessage());
        FieldError priority = ex.fieldErrors().stream()
                .filter(f -> f.field().equals("priority")).findFirst().orElseThrow();
        assertEquals("invalid value", priority.problem());
        assertEquals("P9", priority.actual());
        FieldError labels = ex.fieldErrors().stream()
                .filter(f -> f.field().equals("labels")).findFirst().orElseThrow();
        assertEquals("wrong JSON type", labels.problem());
        assertEquals("array of strings", labels.expected());
        assertEquals("string", labels.actual()); // JSON type of the actual value ("nope" is a string)
    }

    @Test
    void label_items_are_typed_and_indexed() {
        GateValidationException ex = assertThrows(GateValidationException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("title", "t", "labels", List.of("ok", 42, "also-longer-than-thirty-two-characters!")),
                        humanToken));
        String all = ex.fieldErrors().toString() + " || " + ex.getMessage();
        Set<String> fields = ex.fieldErrors().stream().map(FieldError::field).collect(Collectors.toSet());
        assertTrue(fields.contains("labels[1]"), "indexed label problem missing: " + all);
        assertTrue(fields.contains("labels[2]"), "indexed label problem missing: " + all);
        FieldError typed = ex.fieldErrors().stream()
                .filter(f -> f.field().equals("labels[1]")).findFirst().orElseThrow();
        assertEquals("wrong JSON type", typed.problem());
        assertEquals("number", typed.actual());
    }

    @Test
    void mcp_only_fields_are_rejected_as_unknown() {
        // agent_config_id and stage are not in the MCP ticket_create schema (McpToolRegistry).
        GateValidationException ex = assertThrows(GateValidationException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("title", "t", "agent_config_id", "claude-x", "stage", "PENDING"), agentToken));
        Set<String> fields = ex.fieldErrors().stream().map(FieldError::field).collect(Collectors.toSet());
        assertTrue(fields.contains("agent_config_id"), ex.getMessage());
        assertTrue(fields.contains("stage"), ex.getMessage());
        FieldError stage = ex.fieldErrors().stream()
                .filter(f -> f.field().equals("stage")).findFirst().orElseThrow();
        assertEquals("unknown field", stage.problem());
    }

    @Test
    void numeric_title_is_a_type_error_not_a_coercion() {
        GateValidationException ex = assertThrows(GateValidationException.class,
                () -> dispatcher.dispatch("ticket_create", Map.of("title", 42L), agentToken));
        FieldError title = ex.fieldErrors().get(0);
        assertEquals("title", title.field());
        assertEquals("wrong JSON type", title.problem());
        assertEquals("number", title.actual());
    }

    @Test
    void blank_explicit_ticket_no_is_treated_as_omitted_and_succeeds() {
        // The web contract ("blank = omitted") must hold over MCP too: an auto-numbered ticket is
        // created instead of a validation failure.
        Map<String, Object> out = dispatchOk(Map.of("title", "ok", "ticket_no", "   "));
        assertEquals("T-101", out.get("ticket_no"));
    }

    // ------------------------------------------------------------------
    // 2) Domain failures are distinguishable and carry context
    // ------------------------------------------------------------------

    @Test
    void duplicate_ticket_no_is_a_domain_failure_not_validation() {
        GateException ex = assertThrows(GateException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("ticket_no", "T-1", "title", "again"), humanToken));
        assertFalse(ex instanceof GateValidationException,
                "a duplicate is a state rule, not a parameter problem");
        assertTrue(ex.getMessage().contains("ticket already exists: T-1"), ex.getMessage());
    }

    @Test
    void unknown_project_is_a_domain_failure_naming_the_project() {
        GateException ex = assertThrows(GateException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("title", "t", "project_id", "nope"), humanToken));
        assertFalse(ex instanceof GateValidationException);
        assertTrue(ex.getMessage().contains("no such project: nope"), ex.getMessage());
    }

    @Test
    void missing_auth_repo_reports_the_actual_path() {
        // Register a project whose auth repo was never initialized (simulates a torn setup): the
        // failure must carry the concrete repo path, not a bare "auth repo missing".
        Path ghostRepo = harness.root().resolve("auth-ghost.git");
        harness.projects().insert(new Project(
                "ghost", "Ghost", harness.root().resolve("ws-ghost").toString(),
                "refs/heads/main", ghostRepo.toString(), null, null,
                List.of(), false, 0, Instant.now(), Instant.now()));
        GateException ex = assertThrows(GateException.class,
                () -> dispatcher.dispatch("ticket_create",
                        Map.of("title", "t", "project_id", "ghost"), humanToken));
        assertFalse(ex instanceof GateValidationException);
        assertTrue(ex.getMessage().contains("auth repo for this ticket does not exist"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains(ghostRepo.toString()),
                "the message must name the actual repo path: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Protocol-level: error.data carries the layer and field list
    // ------------------------------------------------------------------

    @Test
    void protocol_error_data_distinguishes_validation_from_domain() {
        String validation = call(server, "ticket_create",
                "{\"priority\":\"P9\",\"labels\":\"x\"}");
        assertTrue(validation.contains("-32602"), validation);
        assertTrue(validation.contains("\"layer\":\"validation\""), validation);
        assertTrue(validation.contains("\"fields\""), validation);
        assertTrue(validation.contains("\"field\":\"priority\""), validation);
        assertTrue(validation.contains("\"field\":\"title\""), validation);

        // Duplicate ticket_no: a domain failure with its own layer, and -32602 (fixable/stateful
        // rejection, NOT an opaque -32603 internal error — the T-108 complaint).
        String domain = call(server, "ticket_create", "{\"ticket_no\":\"T-1\",\"title\":\"again\"}");
        assertTrue(domain.contains("-32602"), domain);
        assertTrue(domain.contains("\"layer\":\"domain\""), domain);
        assertTrue(domain.contains("\"error_code\":\"USAGE\""), domain);
        assertFalse(domain.contains("\"fields\""),
                "domain failures must not masquerade as parameter problems: " + domain);
    }

    @Test
    void permission_denials_report_the_permission_layer() {
        // The agent token cannot drive the human-domain review_run tool.
        String denied = callOn(server, "review_run", "{\"ticket_no\":\"T-1\"}", agentToken);
        assertTrue(denied.contains("\"layer\":\"permission\""), denied);
        assertTrue(denied.contains("permission denied"), denied);
    }

    // ------------------------------------------------------------------
    // 3) Every failed MCP call lands in adapters.log
    // ------------------------------------------------------------------

    @Test
    void failed_calls_are_recorded_in_adapters_log_with_request_and_reason() throws Exception {
        // One validation failure and one domain failure through the real server.
        call(server, "ticket_create", "{\"title\":\"  \",\"priority\":\"P9\"}");
        call(server, "ticket_create", "{\"ticket_no\":\"T-1\",\"title\":\"dup\"}");

        String logText = Files.readString(
                Path.of(harness.config().gateHome().toString()).resolve("adapters.log"),
                StandardCharsets.UTF_8);
        assertTrue(logText.contains("mcp.tool_failed"), logText);
        assertTrue(logText.contains("\"tool\":\"ticket_create\""), logText);
        assertTrue(logText.contains("\"layer\":\"validation\""), logText);
        assertTrue(logText.contains("\"layer\":\"domain\""), logText);
        assertTrue(logText.contains("\"reason\":\"invalid ticket_create request"), logText);
        assertTrue(logText.contains("title"), "the log must record the failing field: " + logText);
        assertTrue(logText.contains("ticket already exists: T-1"), logText);
        // Request summary present so the failure is reproducible after the fact.
        assertTrue(logText.contains("\"arguments\":\"{title=  , priority=P9}"), logText);
    }

    @Test
    void successful_calls_are_audited_too() {
        String ok = call(server, "ticket_create", "{\"title\":\"follow-up\",\"priority\":\"P2\"}");
        assertFalse(ok.contains("\"error\""), ok);
        String logText = readLog();
        assertTrue(logText.contains("mcp.tool_ok"), logText);
        assertTrue(logText.contains("\"tool\":\"ticket_create\""), logText);
    }

    // ------------------------------------------------------------------
    // Other tools get the same structured parameter feedback
    // ------------------------------------------------------------------

    @Test
    void presubmit_tools_report_missing_and_mistyped_ticket_no() {
        McpToolDispatcher.ToolException ex = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("presubmit_create", Map.of(), agentToken));
        assertTrue(ex.getMessage().contains("ticket_no: missing required parameter"), ex.getMessage());
        assertTrue(ex.getMessage().contains("presubmit_create"), ex.getMessage());

        McpToolDispatcher.ToolException typed = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("presubmit_get_diff",
                        Map.of("ticket_no", List.of("T-1")), humanToken));
        assertTrue(typed.getMessage().contains("wrong JSON type"), typed.getMessage());
        assertEquals("validation", typed.data().get("layer"));

        McpToolDispatcher.ToolException round = assertThrows(McpToolDispatcher.ToolException.class,
                () -> dispatcher.dispatch("review_run",
                        Map.of("ticket_no", "T-1", "round", "3.5"), humanToken));
        assertTrue(round.getMessage().contains("round"), round.getMessage());
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchOk(Map<String, Object> args) {
        return (Map<String, Object>) dispatcher.dispatch("ticket_create", args, agentToken);
    }

    private String call(McpServer target, String tool, String argsJson) {
        return callOn(target, tool, argsJson, humanToken);
    }

    private String callOn(McpServer target, String tool, String argsJson, String token) {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        McpServer scoped = token.equals(humanToken) ? target
                : new McpServer(dispatcher, token, AdapterLog.at(
                        harness.config().gateHome().resolve("adapters.log")));
        scoped.run(in, ps, err);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String readLog() {
        try {
            return Files.readString(
                    Path.of(harness.config().gateHome().toString()).resolve("adapters.log"),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
