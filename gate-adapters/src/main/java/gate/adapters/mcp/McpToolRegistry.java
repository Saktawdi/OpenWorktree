package gate.adapters.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for which MCP tools exist and which domain each belongs to
 * (架构落地执行文档 §5.4, §11.3, 执行文档 §4 P3 L259-261).
 *
 * <p><b>Two domains, two credential sets:</b>
 * <ul>
 *   <li><b>agent domain</b> (low privilege, bound to one ticket,下发 with the worktree):
 *       {@code presubmit_create}, {@code presubmit_get_diff}, {@code review_result_get}.</li>
 *   <li><b>human/orchestrator domain</b> (high privilege):
 *       {@code review_run}, {@code commit_and_publish}, {@code config_show}, {@code provider_list}.</li>
 * </ul>
 *
 * <p><b>{@code review_run} and {@code commit_and_publish} are NEVER in the agent domain</b> —
 * otherwise an agent could repeatedly run reviews until it lucks into a pass (执行文档 §4 P3).
 *
 * <p><b>List-driven permission tests</b> (§5.4, §11.3): the test suite traverses
 * {@link #humanDomainTools()} with agent-domain credentials and asserts each call is denied. When
 * a new tool is added here, the test automatically covers it — there is no separate registration to
 * forget. A tool not in either set is rejected as unknown.
 */
public final class McpToolRegistry {

    public static final String AGENT_DOMAIN = "agent";
    public static final String HUMAN_DOMAIN = "human";

    private static final Map<String, ToolDef> TOOLS = new LinkedHashMap<>();

    static {
        // --- agent domain (low privilege) ---
        register(new ToolDef(
                "presubmit_create",
                AGENT_DOMAIN,
                "Freeze the agent's worktree into an immutable tree and start a review round. " +
                "This is the ONLY transition an agent may trigger (T2).",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string", "description", "Ticket number")),
                        "required", List.of("ticket_no")))));

        register(new ToolDef(
                "presubmit_get_diff",
                AGENT_DOMAIN,
                "Retrieve the diff text captured for a presubmit round, so the agent can see what " +
                "was submitted for review.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string"),
                                "round", Map.of("type", "integer", "description", "Round number (default: latest)")),
                        "required", List.of("ticket_no")))));

        register(new ToolDef(
                "review_result_get",
                AGENT_DOMAIN,
                "Retrieve the latest review result (verdict + structured findings) for a ticket, so " +
                "the agent can read rejection feedback and fix it.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string"),
                                "round", Map.of("type", "integer", "description", "Round number (default: latest)")),
                        "required", List.of("ticket_no")))));

        // --- human/orchestrator domain (high privilege) ---
        register(new ToolDef(
                "review_run",
                HUMAN_DOMAIN,
                "Run a review round (prism engine or manual verdict). The verdict is minted by " +
                "GatePolicy, never by the agent. NEVER exposed to the agent domain — otherwise an " +
                "agent could repeatedly run reviews until it passes.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string"),
                                "round", Map.of("type", "integer", "description", "Round number (default: latest)")),
                        "required", List.of("ticket_no")))));

        register(new ToolDef(
                "commit_and_publish",
                HUMAN_DOMAIN,
                "Commit the reviewed tree and push it through the gate (commit-tree + approval + " +
                "push-option). NEVER exposed to the agent domain.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string"),
                                "round", Map.of("type", "integer", "description", "Round number (default: latest)")),
                        "required", List.of("ticket_no")))));

        register(new ToolDef(
                "config_show",
                HUMAN_DOMAIN,
                "Show the effective gate configuration (paths, target refs, engine status).",
                schema(Map.of("type", "object", "properties", Map.of()))));

        register(new ToolDef(
                "provider_list",
                HUMAN_DOMAIN,
                "List configured LLM providers and their cached models.",
                schema(Map.of("type", "object", "properties", Map.of()))));
    }

    private McpToolRegistry() {
    }

    /** All tool definitions, in registration order. */
    public static List<ToolDef> all() {
        return List.copyOf(TOOLS.values());
    }

    /** All tool names belonging to the agent domain. */
    public static Set<String> agentDomainTools() {
        return TOOLS.values().stream()
                .filter(t -> AGENT_DOMAIN.equals(t.domain))
                .map(ToolDef::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** All tool names belonging to the human/orchestrator domain. */
    public static Set<String> humanDomainTools() {
        return TOOLS.values().stream()
                .filter(t -> HUMAN_DOMAIN.equals(t.domain))
                .map(ToolDef::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Look up a tool by name; empty if unknown. */
    public static java.util.Optional<ToolDef> find(String name) {
        return java.util.Optional.ofNullable(TOOLS.get(name));
    }

    private static void register(ToolDef def) {
        if (TOOLS.putIfAbsent(def.name, def) != null) {
            throw new IllegalStateException("duplicate tool name: " + def.name);
        }
    }

    private static Map<String, Object> schema(Map<String, Object> raw) {
        return raw;
    }

    /** A tool definition: name, domain, description, and JSON Schema for arguments. */
    public record ToolDef(String name, String domain, String description, Map<String, Object> inputSchema) {
        public ToolDef {
            inputSchema = Map.copyOf(inputSchema);
        }
    }
}
