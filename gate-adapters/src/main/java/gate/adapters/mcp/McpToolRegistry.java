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
 *       {@code presubmit_create}, {@code presubmit_get_diff}, {@code review_result_get},
 *       {@code sync_base}, {@code session_read} — plus
 *       {@code ticket_create}, which is agent-callable but not ticket-bound (its whole point is
 *       creating NEW tickets, e.g. follow-ups discovered mid-work). It is however
 *       <b>project-bound</b>: the new ticket must live in the bound ticket's project
 *       (McpToolDispatcher#projectScope — a cross-project or unprojected creation once sent one
 *       project's dispatch to the gate-level repo, surfacing it under every project board).
 *       {@code ticket_edit} is <b>ticket-bound</b>: it edits the agent's own ticket only (a
 *       different {@code ticket_no} is denied by the dispatch-level binding check, exactly like
 *       the presubmit tools) and accepts editable work-item metadata (title / priority /
 *       description / note / labels) <b>only</b> — no stage key exists in its schema or in its
 *       parser, so an agent has no entry point to a state transition.</li>
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
                "ticket_create",
                AGENT_DOMAIN,
                "Create a new follow-up ticket in the gate: validates the request, cuts the ticket "
                + "branch from the base tip and materializes its worktree clone. Project-scoped for "
                + "agents: the new ticket is always bound to the project of YOUR bound ticket — "
                + "omit project_id to inherit it (recommended); any other project is denied. "
                + "Returns the assigned ticket_no and clone_path.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description",
                                        "Short ticket title (required)"),
                                "project_id", Map.of("type", "string", "description",
                                        "Registered project id to bind the ticket to. Agents: omit "
                                                + "to inherit your bound ticket's project; a "
                                                + "different project is denied. Humans may pass "
                                                + "any project; omit for the gate-level default repo"),
                                "ticket_no", Map.of("type", "string", "description",
                                        "Optional explicit ticket number; auto-generated (next T-nnn) "
                                                + "when omitted"),
                                "target_branch", Map.of("type", "string", "description",
                                        "Optional ticket branch name; defaults to the ticket number"),
                                "priority", Map.of("type", "string", "description",
                                        "Optional priority: P0, P1, P2 or P3"),
                                "description", Map.of("type", "string",
                                        "description", "What this ticket should accomplish"),
                                "note", Map.of("type", "string", "description",
                                        "Free-form notes for the human reviewer"),
                                "labels", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Optional labels (max 20, each max 32 chars)")),
                        "required", List.of("title")))));

        register(new ToolDef(
                "ticket_edit",
                AGENT_DOMAIN,
                "Edit a ticket's work-item metadata: title, priority, description, note and labels. "
                + "Only the fields you pass are touched — omit one to leave it as it is; pass an "
                + "empty string (or [] for labels) to clear an optional field. Sending a field as "
                + "JSON null counts as omitting it. "
                + "The gate flow owns the ticket's stage: there is deliberately NO stage parameter "
                + "here, so an agent can never move a ticket through the board — presubmit_create "
                + "remains the only transition an agent may trigger, and review/publish/restart/"
                + "cancel stay in the human domain. "
                + "Scope: an agent may edit only the ticket its credential is bound to — "
                + "ticket_no may be omitted (recommended: it then means your own ticket) but "
                + "naming any other ticket is denied. Human tokens may edit any ticket. "
                + "Returns the updated ticket.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string", "description",
                                        "Ticket to edit; defaults to the ticket your credential "
                                                + "is bound to"),
                                "title", Map.of("type", "string", "description",
                                        "New title (must not be blank)"),
                                "priority", Map.of("type", "string", "description",
                                        "New priority: P0, P1, P2 or P3; empty string clears it"),
                                "description", Map.of("type", "string", "description",
                                        "New requirement description; empty string clears it"),
                                "note", Map.of("type", "string", "description",
                                        "New free-form note for the human reviewer; empty string "
                                                + "clears it"),
                                "labels", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description",
                                        "Replaces the whole label list (max 20, each max 32 "
                                                + "chars); [] clears them")),
                        "required", List.of()))));

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

        register(new ToolDef(
                "sync_base",
                AGENT_DOMAIN,
                "Fast-forward this ticket's clone and its authoritative branch onto the latest tip "
                + "of the project's base branch. Uncommitted worktree changes "
                + "(including untracked files) are stashed and replayed by default (allow_dirty=true; "
                + "conflict markers are left in the worktree for you to resolve); pass "
                + "allow_dirty=false to skip a dirty clone untouched instead. Refused while a review "
                + "round is open (the reviewed diff is pinned to its base), for terminal tickets "
                + "(restart the ticket first) and for quick-mode super tickets, which work on the "
                + "project workspace itself and need no base sync.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ticket_no", Map.of("type", "string", "description", "Ticket number"),
                                "allow_dirty", Map.of("type", "boolean", "description",
                                        "Stash and replay uncommitted changes (default true); "
                                                + "false skips a dirty clone untouched")),
                        "required", List.of("ticket_no")))));

        register(new ToolDef(
                "session_read",
                AGENT_DOMAIN,
                "Read a session's transcript, read-only: session metadata plus its messages in "
                + "chronological order (role, text, thinking, tool calls and their results). "
                + "Messages carry their absolute index in the full transcript; the response returns "
                + "the newest ones by default (limit 20, max 100) and pages backwards — pass the "
                + "returned next_before_index back as before_index to walk towards older messages. "
                + "Long fields are clipped with an explicit truncation marker and the whole response "
                + "is size-capped, so page for more. Scope: an agent may read sessions whose ticket "
                + "belongs to the project of the agent's OWN ticket (a session of another project is "
                + "denied); human tokens may read any session.",
                schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "session_id", Map.of("type", "string", "description",
                                        "Session id to read"),
                                "limit", Map.of("type", "integer", "description",
                                        "How many messages to return, counted from the newest "
                                                + "(default 20, max 100)"),
                                "before_index", Map.of("type", "integer", "description",
                                        "Only return messages with index < before_index — pass the "
                                                + "previous response's next_before_index to page "
                                                + "towards older messages")),
                        "required", List.of("session_id")))));

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
