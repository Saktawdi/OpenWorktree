package gate.adapters.mcp;

import gate.application.presubmit.PresubmitCommand;
import gate.application.presubmit.PresubmitResult;
import gate.application.publish.PublishCommand;
import gate.application.publish.PublishResult;
import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.application.ticket.CreateTicketCommand;
import gate.application.ticket.TicketRequestParser;
import gate.domain.config.GateConfig;
import gate.domain.error.FieldError;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.Decision;
import gate.ports.store.BlobStore;
import gate.ports.store.CredentialRepository;
import gate.ports.store.CredentialRepository.Domain;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.ProviderRepository;
import gate.ports.store.ReviewResultRepository;
import gate.application.GateService;
import gate.domain.blob.BlobRef;
import gate.ports.store.TicketRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches {@code tools/call} requests to {@link GateService} use cases, after enforcing the
 * server-side domain check (架构落地执行文档 §5.4, §11.3, §1.3).
 *
 * <p><b>The domain check is authoritative on the server side.</b> The CLI's {@code --allowedTools}
 * is an agent-controllable additive defence (§1.3 acknowledges CLI flags are agent-controllable
 * config and cannot be the sole defence). Every tool call validates the credential against the
 * {@link CredentialRepository} before dispatching.
 *
 * <p>Tools are thin wrappers over {@link GateService} — they return structured data, not formatted
 * strings, and know nothing about exit codes (§5.4).
 */
public final class McpToolDispatcher {

    private final GateService gateService;
    private final CredentialRepository credentials;
    private final PresubmitRepository presubmits;
    private final ReviewResultRepository reviewResults;
    private final BlobStore blobStore;
    private final ProviderRepository providers;
    private final GateConfig config;
    private final TicketRepository tickets;

    public McpToolDispatcher(GateService gateService, CredentialRepository credentials,
                             PresubmitRepository presubmits, ReviewResultRepository reviewResults,
                             BlobStore blobStore, ProviderRepository providers, GateConfig config,
                             TicketRepository tickets) {
        this.gateService = gateService;
        this.credentials = credentials;
        this.presubmits = presubmits;
        this.reviewResults = reviewResults;
        this.blobStore = blobStore;
        this.providers = providers;
        this.config = config;
        this.tickets = tickets;
    }

    /**
     * Dispatches a tool call.
     *
     * @param toolName   the MCP tool name (must be in {@link McpToolRegistry})
     * @param arguments  the tool arguments (parsed JSON object)
     * @param token      the domain token (from env var, validated against the DB)
     * @return the result content as a map (to be serialised as JSON-RPC result)
     * @throws PermissionDeniedException if the credential's domain is insufficient
     * @throws ToolException             for any other error (wrapped GateException etc.)
     */
    public Map<String, Object> dispatch(String toolName, Map<String, Object> arguments, String token) {
        McpToolRegistry.ToolDef tool = McpToolRegistry.find(toolName)
                .orElseThrow(() -> new ToolException(McpJsonRpc.METHOD_NOT_FOUND,
                        "unknown tool: " + toolName,
                        Map.of("layer", "protocol", "tool", toolName)));

        Domain domain = credentials.validate(token);

        // Server-side domain check: authoritative (§1.3).
        if (!canCall(domain, tool)) {
            throw new PermissionDeniedException(tool.name(), tool.domain(), domain.name());
        }

        // Agent domain tokens are bound to a specific ticket — enforce ticket-scoping.
        if (domain.isAgent() && hasTicketParam(tool)) {
            String requestedTicket = strArg(arguments, "ticket_no");
            if (requestedTicket != null && !requestedTicket.equals(domain.ticketNo())) {
                throw new PermissionDeniedException(tool.name(),
                        "agent domain (ticket-bound)", "agent domain token bound to ticket "
                                + domain.ticketNo() + " cannot operate on ticket " + requestedTicket);
            }
        }

        return switch (tool.name()) {
            case "ticket_create" -> ticketCreate(arguments, domain);
            case "presubmit_create" -> presubmitCreate(arguments);
            case "presubmit_get_diff" -> presubmitGetDiff(arguments);
            case "review_result_get" -> reviewResultGet(arguments);
            case "sync_base" -> syncBase(arguments);
            case "review_run" -> reviewRun(arguments);
            case "commit_and_publish" -> commitAndPublish(arguments);
            case "config_show" -> configShow();
            case "provider_list" -> providerList();
            default -> throw new ToolException(McpJsonRpc.METHOD_NOT_FOUND,
                    "unknown tool: " + toolName,
                    Map.of("layer", "protocol", "tool", toolName));
        };
    }

    /** Whether the given domain may call the tool. */
    static boolean canCall(Domain domain, McpToolRegistry.ToolDef tool) {
        if (!domain.isValid()) {
            return false;
        }
        if (McpToolRegistry.HUMAN_DOMAIN.equals(tool.domain())) {
            return domain.isHuman();
        }
        if (McpToolRegistry.AGENT_DOMAIN.equals(tool.domain())) {
            return domain.isAgent() || domain.isHuman(); // human can do everything agent can
        }
        return false;
    }

    private static boolean hasTicketParam(McpToolRegistry.ToolDef tool) {
        return "presubmit_create".equals(tool.name())
                || "presubmit_get_diff".equals(tool.name())
                || "review_result_get".equals(tool.name())
                || "sync_base".equals(tool.name());
    }

    // --- tool implementations ---

    private Map<String, Object> ticketCreate(Map<String, Object> args, Domain domain) {
        // Field-level validation (missing/type/enum/format) happens here, in the same parser the
        // web API uses — one rule set for both entry points (T-108). Any violation throws a
        // GateValidationException whose message and error.data name every broken field.
        CreateTicketCommand parsed = TicketRequestParser.parse(args, TicketRequestParser.MCP_KEYS);
        String projectId = projectScope(domain, parsed.projectId());
        // agent_config_id is a web-only binding (it decides which agent CLI a session spawns) and
        // must never be settable through the agent-facing MCP tool.
        gate.domain.ticket.Ticket t = gateService.createTicket(new CreateTicketCommand(
                parsed.ticketNo(),
                parsed.title(),
                projectId,
                parsed.targetBranch(),
                parsed.stage(),
                parsed.priority(),
                parsed.description(),
                parsed.note(),
                parsed.labels(),
                null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", t.ticketNo());
        result.put("title", t.title());
        result.put("target_ref", t.targetRef());
        result.put("clone_path", t.clonePath());
        result.put("stage", t.stage().name());
        result.put("project_id", t.projectId());
        result.put("priority", t.priority());
        result.put("labels", t.labels());
        return result;
    }

    /**
     * The project a {@code ticket_create} call may bind its new ticket to.
     *
     * <p>Agent-domain tokens are bound to one ticket (the session's ticket). The new ticket must
     * stay inside that ticket's project — the T-130 dispatch incident: an agent of the 姬姬云村
     * project called {@code ticket_create} without {@code project_id} and every follow-up ticket
     * fell to the gate-level default repo, surfacing under all project boards (unaffiliated
     * tickets render in every board). Scoping is therefore authoritative on the server side, like
     * the ticket_no check: an explicit {@code project_id} that differs from the bound ticket's
     * project is denied, and an omitted one inherits the bound ticket's project (an agent cannot
     * be asked to guess its own project id). An agent whose bound ticket is unaffiliated may only
     * create unaffiliated tickets. Human/orchestrator tokens stay unrestricted.
     *
     * @param requestedProjectId raw {@code project_id} argument (blank treated as omitted, same
     *                           normalization as the web controller)
     * @return the effective project id (never a cross-project value)
     */
    private String projectScope(Domain domain, String requestedProjectId) {
        String requested = requestedProjectId == null || requestedProjectId.isBlank()
                ? null : requestedProjectId.trim();
        if (!domain.isAgent()) {
            return requested;
        }
        String boundTicketNo = domain.ticketNo();
        if (boundTicketNo == null || boundTicketNo.isBlank()) {
            throw new PermissionDeniedException("ticket_create", "agent project scope",
                    "agent domain token carries no ticket binding; the allowed project for "
                            + "ticket_create cannot be determined");
        }
        gate.domain.ticket.Ticket bound = tickets.find(boundTicketNo).orElse(null);
        if (bound == null) {
            // Fail closed: without the bound ticket the project scope is unresolvable.
            throw new PermissionDeniedException("ticket_create", "agent project scope",
                    "agent domain token is bound to ticket " + boundTicketNo
                            + " which no longer exists; the allowed project for ticket_create "
                            + "cannot be determined");
        }
        String scoped = bound.projectId();
        if (requested != null && !requested.equals(scoped)) {
            throw new PermissionDeniedException("ticket_create", "agent project scope",
                    "agent domain token bound to ticket " + boundTicketNo + " (project "
                            + (scoped == null ? "<none>" : scoped) + ") cannot create a ticket "
                            + "in project " + requested);
        }
        return scoped;
    }

    private Map<String, Object> presubmitCreate(Map<String, Object> args) {
        String ticketNo = ticketNoArg("presubmit_create", args);
        PresubmitResult r = gateService.presubmit(new PresubmitCommand(ticketNo));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", r.ticketNo());
        result.put("review_round", r.reviewRound());
        result.put("tree_hash", r.treeHash());
        result.put("base_commit", r.baseCommit());
        result.put("target_ref", r.targetRef());
        result.put("diff_bytes", r.diffBytes());
        result.put("changed_paths", r.changedPaths());
        result.put("integrity_warnings", r.integrity().warnings().stream()
                .map(v -> v.rule() + ":" + v.detail()).toList());
        return result;
    }

    private Map<String, Object> presubmitGetDiff(Map<String, Object> args) {
        String tool = "presubmit_get_diff";
        String ticketNo = ticketNoArg(tool, args);
        Integer round = roundArg(tool, args);
        var row = (round == null ? presubmits.findLatest(ticketNo) : presubmits.find(ticketNo, round))
                .orElseThrow(() -> new ToolException(McpJsonRpc.INVALID_PARAMS,
                        "no presubmit round for " + ticketNo
                                + (round == null ? "" : "/" + round),
                        domainData(tool, "no presubmit round for " + ticketNo
                                + (round == null ? "" : "/" + round))));
        byte[] diff = blobStore.get(new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256()));
        String diffText = new String(diff, StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", ticketNo);
        result.put("review_round", row.reviewRound());
        result.put("tree_hash", row.treeHash().hex());
        result.put("base_commit", row.baseCommit().hex());
        result.put("diff", diffText);
        return result;
    }

    private Map<String, Object> reviewResultGet(Map<String, Object> args) {
        String tool = "review_result_get";
        String ticketNo = ticketNoArg(tool, args);
        Integer round = roundArg(tool, args);
        var presubmitRow = (round == null
                ? presubmits.findLatest(ticketNo) : presubmits.find(ticketNo, round))
                .orElseThrow(() -> new ToolException(McpJsonRpc.INVALID_PARAMS,
                        "no presubmit round for " + ticketNo
                                + (round == null ? "" : "/" + round),
                        domainData(tool, "no presubmit round for " + ticketNo
                                + (round == null ? "" : "/" + round))));
        var reviewRow = reviewResults.findLatestForPresubmit(presubmitRow.id())
                .orElseThrow(() -> new ToolException(McpJsonRpc.INVALID_PARAMS,
                        "no review result for " + ticketNo + " round " + presubmitRow.reviewRound(),
                        domainData(tool, "no review result for " + ticketNo
                                + " round " + presubmitRow.reviewRound())));

        byte[] findingsBytes = blobStore.get(new BlobRef(reviewRow.findingsBlobPath(), 0, "0".repeat(64)));
        String findingsJson = new String(findingsBytes, StandardCharsets.UTF_8);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", ticketNo);
        result.put("review_round", presubmitRow.reviewRound());
        result.put("verdict", reviewRow.verdict().name());
        result.put("engine_id", reviewRow.engine().engineId());
        result.put("covered_ok", reviewRow.coveredOk());
        result.put("degraded", reviewRow.degraded());
        result.put("findings", findingsJson);
        return result;
    }

    /**
     * T-118 基座同步, exposed to agents: fast-forward the ticket's clone and authoritative branch
     * onto the base-branch tip, stashing and replaying uncommitted worktree changes by default
     * (same semantics as the manual web button; the session-start auto-sync skips dirty clones
     * instead). No ticket lock here, matching {@code presubmit_create}: the calling agent session
     * is the de-facto worktree owner, and the web-side lock would refuse the very session the
     * call comes from.
     */
    private Map<String, Object> syncBase(Map<String, Object> args) {
        String tool = "sync_base";
        String ticketNo = ticketNoArg(tool, args);
        boolean allowDirty = Boolean.TRUE.equals(boolArg(tool, args, "allow_dirty", true));
        var r = gateService.syncBase(
                new gate.application.basesync.SyncBaseCommand(ticketNo, allowDirty, "agent"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", ticketNo);
        result.put("status", r.status());
        result.put("behind", r.behind());
        result.put("from_tip", r.fromTip());
        result.put("to_tip", r.toTip());
        result.put("branch_moved", r.branchMoved());
        result.put("conflicts", r.conflicts());
        result.put("stash_kept", r.stashKept());
        if (r.skippedReason() != null) {
            result.put("skipped_reason", r.skippedReason());
        }
        if (r.importKind() != null) {
            result.put("import_kind", r.importKind());
            if (r.importReason() != null) {
                result.put("import_reason", r.importReason());
            }
        }
        return result;
    }

    private Map<String, Object> reviewRun(Map<String, Object> args) {
        String tool = "review_run";
        String ticketNo = ticketNoArg(tool, args);
        Integer round = roundArg(tool, args);
        ReviewResult r = gateService.review(ReviewCommand.forEngine(ticketNo, round));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", r.ticketNo());
        result.put("review_round", r.reviewRound());
        result.put("tree_hash", r.treeHash());
        result.put("dangling_commit", r.danglingCommit());
        result.put("verdict", r.verdict().name());
        result.put("reason", r.reason());
        result.put("detail", r.detail());
        return result;
    }

    private Map<String, Object> commitAndPublish(Map<String, Object> args) {
        String tool = "commit_and_publish";
        String ticketNo = ticketNoArg(tool, args);
        Integer round = roundArg(tool, args);
        PublishResult r = gateService.publish(new PublishCommand(ticketNo, round));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket_no", r.ticketNo());
        result.put("review_round", r.reviewRound());
        result.put("tree_hash", r.treeHash());
        result.put("commit_sha", r.commitSha());
        result.put("target_ref", r.targetRef());
        result.put("ref_before", r.refBefore());
        result.put("ref_after", r.refAfter());
        result.put("already_published", r.alreadyPublished());
        return result;
    }

    private Map<String, Object> configShow() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", config.project());
        result.put("auth_repo", config.authRepo().toString());
        result.put("clones_root", config.clonesRoot().toString());
        result.put("target_ref_whitelist", config.targetRefWhitelist());
        result.put("gate_home", config.gateHome().toString());
        result.put("engine_configured", config.engineConfigured());
        return result;
    }

    private Map<String, Object> providerList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProviderRepository.ProviderRow p : providers.findAll()) {
            List<String> models = providers.models(p.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("name", p.name());
            row.put("base_url", p.baseUrl());
            row.put("type", p.type());
            row.put("model_count", models.size());
            row.put("models", models);
            rows.add(row);
        }
        return Map.of("providers", rows);
    }

    // --- helpers ---

    /**
     * Required {@code ticket_no} for the ticket-bound tools. Reports missing / blank / wrong-type
     * as a structured {@link ToolException} naming the field and the accepted shape (T-108).
     */
    private static String ticketNoArg(String tool, Map<String, Object> args) {
        List<FieldError> problems = new ArrayList<>();
        Object v = args.get("ticket_no");
        if (v == null) {
            problems.add(new FieldError("ticket_no", "missing required parameter", "non-blank string", null));
        } else if (!(v instanceof String s)) {
            problems.add(new FieldError("ticket_no", "wrong JSON type", "string", jsonType(v)));
        } else if (s.isBlank()) {
            problems.add(new FieldError("ticket_no", "must not be blank", "non-blank string", null));
        }
        if (!problems.isEmpty()) {
            throw validation(tool, problems);
        }
        return ((String) v).trim();
    }

    /** Optional {@code round}: must be an integral JSON number or a decimal-string integer. */
    private static Integer roundArg(String tool, Map<String, Object> args) {
        Object v = args.get("round");
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw validation(tool, List.of(new FieldError("round",
                        "must be an integer", "integer", s)));
            }
        }
        if (v instanceof Long || v instanceof Integer) {
            return ((Number) v).intValue();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return (int) d;
            }
            throw validation(tool, List.of(new FieldError("round",
                    "must be an integer", "integer", String.valueOf(v))));
        }
        throw validation(tool, List.of(new FieldError("round",
                "wrong JSON type", "integer", jsonType(v))));
    }

    /**
     * Optional boolean argument: JSON {@code true}/{@code false} or the string forms
     * {@code "true"}/{@code "false"} (agent CLIs sometimes stringify); {@code null} → default.
     */
    private static boolean boolArg(String tool, Map<String, Object> args, String key, boolean dflt) {
        Object v = args.get(key);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(t)) {
                return true;
            }
            if ("false".equals(t)) {
                return false;
            }
            throw validation(tool, List.of(new FieldError(key,
                    "must be a boolean", "boolean", s)));
        }
        throw validation(tool, List.of(new FieldError(key,
                "wrong JSON type", "boolean", jsonType(v))));
    }

    private static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String jsonType(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Map<?, ?>) {
            return "object";
        }
        if (v instanceof List<?>) {
            return "array";
        }
        return v.getClass().getSimpleName();
    }

    /** Builds a parameter-validation ToolException carrying the structured problem list. */
    private static ToolException validation(String tool, List<FieldError> problems) {
        List<FieldError> copy = List.copyOf(problems);
        String detail = copy.stream().map(FieldError::render).collect(java.util.stream.Collectors.joining("; "));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "validation");
        data.put("tool", tool);
        data.put("fields", copy.stream().map(FieldError::toMap).toList());
        return new ToolException(McpJsonRpc.INVALID_PARAMS,
                "invalid " + tool + " arguments: " + detail, data);
    }

    /** Domain/state failure data (e.g. "no presubmit round"): not a fixable parameter problem. */
    private static Map<String, Object> domainData(String tool, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", "domain");
        data.put("tool", tool);
        data.put("reason", reason);
        return data;
    }

    /** Thrown when a credential's domain is insufficient for the requested tool. */
    public static final class PermissionDeniedException extends RuntimeException {
        final String toolName;
        final String requiredDomain;
        final String actualDomain;

        PermissionDeniedException(String toolName, String requiredDomain, String actualDomain) {
            super("permission denied: tool '" + toolName + "' requires " + requiredDomain
                    + " domain, but credential is " + actualDomain);
            this.toolName = toolName;
            this.requiredDomain = requiredDomain;
            this.actualDomain = actualDomain;
        }
    }

    /** Thrown for tool-level errors (bad params, unknown tool, business logic failures). */
    public static final class ToolException extends RuntimeException {
        final int rpcCode;
        final Map<String, Object> data;

        ToolException(int rpcCode, String message) {
            this(rpcCode, message, null, null);
        }

        ToolException(int rpcCode, String message, Throwable cause) {
            this(rpcCode, message, cause, null);
        }

        ToolException(int rpcCode, String message, Map<String, Object> data) {
            this(rpcCode, message, null, data);
        }

        ToolException(int rpcCode, String message, Throwable cause, Map<String, Object> data) {
            super(message, cause);
            this.rpcCode = rpcCode;
            this.data = data;
        }

        /** Structured payload for JSON-RPC {@code error.data}; may be null. */
        public Map<String, Object> data() {
            return data;
        }
    }
}
