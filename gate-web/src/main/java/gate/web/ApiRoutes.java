package gate.web;

import gate.application.GateService;
import gate.application.MetricsService;
import gate.application.PresubmitCommand;
import gate.application.PresubmitResult;
import gate.application.ReconcileCommand;
import gate.application.ReconcileResult;
import gate.application.StatusQuery;
import gate.application.StatusResult;
import gate.domain.blob.BlobRef;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.Session;
import gate.domain.task.GateTask;
import gate.ports.AgentConfigRepository;
import gate.ports.AgentSessionPort;
import gate.ports.BlobStore;
import gate.ports.PresubmitRepository;
import gate.ports.ProjectRepository;
import gate.ports.ProviderRepository;
import gate.ports.ReviewResultRepository;
import gate.ports.SessionRepository;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import gate.adapters.git.GitCli;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The S1 read-only + synchronous {@code /api/*} routes (执行文档-后端-web §4.1, §10 S1).
 *
 * <p>Each route is a thin driver over {@link GateService} / the repositories, returning structured
 * data that {@link ApiHandler} serialises to JSON. It reuses the exact application types the MCP
 * dispatcher and CLI use — no verdict is minted here, no exit code is known here (§5.4). The JSON
 * field shapes mirror {@code McpToolDispatcher} so the two driver adapters stay contract-compatible.
 *
 * <p>Async operations (review/publish) are NOT here — they land in S2 as {@code GateTask} + SSE.
 */
public final class ApiRoutes {

    private final GateService gateService;
    private final MetricsService metricsService;
    private final TopologyInitializer topologyInitializer;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final ReviewResultRepository reviewResults;
    private final BlobStore blobStore;
    private final ProviderRepository providers;
    private final GateConfig config;
    private final gate.ports.Clock clock;
    private final TaskRegistry taskRegistry;
    private final TaskRunner taskRunner;
    private final AgentConfigRepository agentConfigs;
    private final SessionRepository sessionRepository;
    private final AgentSessionPort agentSessionPort;
    private final TicketLockManager ticketLockManager;
    private final ProjectRepository projects;
    private final ProviderModelFetcher modelFetcher;
    private final RuntimeInfoService runtimeInfo;
    private final GitCli git;
    private final StatusRoutes statusRoutes;
    private final gate.web.project.ProjectRoutes projectRoutes;

    ApiRoutes(WebComponents c) {
        this.gateService = c.gateService();
        this.metricsService = c.metricsService();
        this.topologyInitializer = c.topologyInitializer();
        this.tickets = c.ticketRepository();
        this.presubmits = c.presubmitRepository();
        this.reviewResults = c.reviewResultRepository();
        this.blobStore = c.blobStore();
        this.providers = c.providerRepository();
        this.config = c.config();
        this.clock = c.clock();
        this.taskRegistry = c.taskRegistry();
        this.taskRunner = c.taskRunner();
        this.agentConfigs = c.agentConfigRepository();
        this.sessionRepository = c.sessionRepository();
        this.agentSessionPort = c.agentSessionPort();
        this.ticketLockManager = c.ticketLockManager();
        this.projects = c.projectRepository();
        this.modelFetcher = c.modelFetcher();
        this.runtimeInfo = c.runtimeInfo();
        this.git = c.git();
        this.statusRoutes = new StatusRoutes(gateService, config, runtimeInfo);
        this.projectRoutes = new gate.web.project.ProjectRoutes(projects, topologyInitializer, config);
    }

    /** A resolved response: HTTP status + a JSON-serialisable body. */
    public record Response(int status, Object body) {
    }

    /**
     * Routes an authorised {@code /api/*} request. Throws {@link GateException} for gate-level
     * failures ({@link ApiHandler} maps to HTTP); returns a 404 {@link Response} for unknown paths.
     */
    Response route(String method, String path, String requestBody) {
        String[] seg = split(path);
        // seg[0] == "api"

        if (seg.length == 2 && seg[1].equals("status") && method.equals("GET")) {
            return statusRoutes.status();
        }
        // V5 web console: real runtime environment (服务状态运行环境).
        if (seg.length == 2 && seg[1].equals("runtime") && method.equals("GET")) {
            return statusRoutes.runtime();
        }
        // Agent settings: detected local CLIs plus models exposed by the CLI itself.
        if (seg.length == 2 && seg[1].equals("agent-runtimes") && method.equals("GET")) {
            return statusRoutes.agentRuntimes();
        }
        // V5 web console: codex-style workspace picker (POST carries the path — no query-string seam).
        if (seg.length == 2 && seg[1].equals("workspaces")) {
            if (method.equals("GET")) {
                return projectRoutes.workspaces(null);
            }
            if (method.equals("POST")) {
                return projectRoutes.workspaces(str(parseObject(requestBody), "path"));
            }
        }
        // V5 web console: project registry (select workspace → create project).
        if (seg.length == 2 && seg[1].equals("projects")) {
            if (method.equals("GET")) {
                return projectRoutes.projectList();
            }
            if (method.equals("POST")) {
                return projectCreate(requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("projects")) {
            if (method.equals("PUT")) {
                return projectUpdate(seg[2], requestBody);
            }
            if (method.equals("DELETE")) {
                return projectDelete(seg[2]);
            }
        }
        // Project-scoped ticket board: one project owns one board with N tickets.
        if (seg.length == 4 && seg[1].equals("projects") && seg[3].equals("tickets")) {
            requireProject(seg[2]);
            if (method.equals("GET")) {
                return ticketList(seg[2]);
            }
            if (method.equals("POST")) {
                return ticketCreate(requestBody, seg[2]);
            }
        }
        if (seg.length == 5 && seg[1].equals("projects") && seg[3].equals("tickets")
                && method.equals("GET")) {
            return ticketDetail(seg[2], seg[4]);
        }
        if (seg.length == 5 && seg[1].equals("projects") && seg[3].equals("tickets")
                && (method.equals("PATCH") || method.equals("PUT"))) {
            return ticketUpdate(seg[2], seg[4], requestBody);
        }
        if (seg.length == 5 && seg[1].equals("providers") && seg[3].equals("models")
                && seg[4].equals("fetch") && method.equals("POST")) {
            return providerModelsFetch(seg[2]);
        }
        if (seg.length == 2 && seg[1].equals("reconcile") && method.equals("POST")) {
            return reconcile(requestBody);
        }
        if (seg.length == 2 && seg[1].equals("metrics") && method.equals("GET")) {
            return metrics();
        }
        if (seg.length == 3 && seg[1].equals("metrics") && seg[2].equals("h1") && method.equals("GET")) {
            return metricsH1();
        }
        if (seg.length == 2 && seg[1].equals("config") && method.equals("GET")) {
            return configView();
        }
        if (seg.length == 2 && seg[1].equals("providers") && method.equals("GET")) {
            return providerList();
        }
        if (seg.length == 2 && seg[1].equals("providers") && method.equals("POST")) {
            return providerCreate(requestBody);
        }
        if (seg.length == 3 && seg[1].equals("providers")) {
            if (method.equals("PUT")) {
                return providerUpdate(seg[2], requestBody);
            }
            if (method.equals("DELETE")) {
                return providerDelete(seg[2]);
            }
        }
        if (seg.length == 4 && seg[1].equals("providers") && seg[3].equals("models")
                && method.equals("PUT")) {
            return providerModelsUpdate(seg[2], requestBody);
        }
        if (seg.length == 2 && seg[1].equals("tickets")) {
            if (method.equals("GET")) {
                return ticketList();
            }
            if (method.equals("POST")) {
                return ticketCreate(requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("tickets") && method.equals("GET")) {
            return ticketDetail(seg[2]);
        }
        // V5: editable ticket metadata (priority/title/queue stage).
        if (seg.length == 3 && seg[1].equals("tickets") && (method.equals("PATCH") || method.equals("PUT"))) {
            return ticketUpdate(seg[2], requestBody);
        }
        // V5: live working-tree diff of the ticket clone (replaces the UI's sample diff).
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("diff")
                && method.equals("GET")) {
            return workingDiff(seg[2]);
        }
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("presubmit")
                && method.equals("POST")) {
            return presubmit(seg[2]);
        }
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("review-result")
                && method.equals("GET")) {
            return reviewResult(seg[2]);
        }
        // /api/tickets/{no}/presubmit/{round}/diff
        if (seg.length == 6 && seg[1].equals("tickets") && seg[3].equals("presubmit")
                && seg[5].equals("diff") && method.equals("GET")) {
            return presubmitDiff(seg[2], seg[4]);
        }

        // S2 async gate operations (§4.1, §4.3): 202 + task id, SSE follows on /api/tasks/{id}/events.
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("review")
                && method.equals("POST")) {
            return review(seg[2], requestBody);
        }
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("publish")
                && method.equals("POST")) {
            return publish(seg[2], requestBody);
        }
        if (seg.length == 3 && seg[1].equals("tasks") && method.equals("GET")) {
            return taskDetail(seg[2]);
        }

        // S3 AgentConfig CRUD + session history (执行文档-后端-web §4.1).
        if (seg.length == 2 && seg[1].equals("agent-configs")) {
            if (method.equals("GET")) {
                return agentConfigList();
            }
            if (method.equals("POST")) {
                return agentConfigCreate(requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("agent-configs")) {
            if (method.equals("GET")) {
                return agentConfigDetail(seg[2]);
            }
            if (method.equals("PUT")) {
                return agentConfigUpdate(seg[2], requestBody);
            }
            if (method.equals("DELETE")) {
                return agentConfigDelete(seg[2]);
            }
        }
        if (seg.length == 4 && seg[1].equals("agent-configs") && seg[3].equals("sessions")
                && method.equals("GET")) {
            return agentConfigSessions(seg[2]);
        }

        // S4 session routes (执行文档-后端-web §4.1 会话路由约定).
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("sessions")) {
            if (method.equals("GET")) {
                return ticketSessions(seg[2]);
            }
            if (method.equals("POST")) {
                return sessionCreate(seg[2], requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("sessions") && method.equals("GET")) {
            return sessionDetail(seg[2]);
        }
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("messages")) {
            if (method.equals("GET")) {
                return sessionHistory(seg[2]);
            }
            if (method.equals("POST")) {
                return sessionSend(seg[2], requestBody);
            }
        }
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("abort")
                && method.equals("POST")) {
            return sessionAbort(seg[2]);
        }

        return new Response(404, null); // ApiHandler renders the NOT_FOUND envelope
    }

    // --- status ---------------------------------------------------------------------------------

    private Response status() {
        StatusResult r = gateService.status(new StatusQuery(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target_ref", r.targetRef());
        body.put("auth_tip", r.authTip());
        body.put("auth_commit_count", r.authCommitCount());
        List<Map<String, Object>> ts = new ArrayList<>();
        for (StatusResult.TicketStatus t : r.tickets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket_no", t.ticketNo());
            m.put("stage", t.stage());
            m.put("latest_round", t.latestRound());
            m.put("latest_tree_hash", t.latestTreeHash());
            m.put("latest_intent_status", t.latestIntentStatus());
            m.put("latest_commit_sha", t.latestCommitSha());
            m.put("published_in_auth", t.publishedInAuth());
            ts.add(m);
        }
        body.put("tickets", ts);
        return new Response(200, body);
    }

    // --- tickets ---------------------------------------------------------------------------------

    private Response ticketList() {
        return ticketList(null);
    }

    private Response ticketList(String projectId) {
        Map<String, String> projectNames = projectNameIndex();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Ticket> rows = projectId == null ? tickets.findAll() : tickets.findAllByProject(projectId);
        for (Ticket t : rows) {
            out.add(ticketJson(t, projectNames));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tickets", out);
        return new Response(200, body);
    }

    private Response ticketDetail(String ticketNo) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        return new Response(200, ticketJson(t, projectNameIndex()));
    }

    private Response ticketDetail(String projectId, String ticketNo) {
        Ticket t = ticketInProject(projectId, ticketNo);
        return new Response(200, ticketJson(t, projectNameIndex()));
    }

    /**
     * Web takes over clone generation (D3, §4.2): mirrors {@code TicketCommand.Create} exactly, only
     * the driver changes from picocli to HTTP. Body: {@code {"ticket_no": "...", "title": "...",
     * "description": "..."?, "note": "..."?, "labels": [...]?, "agent_config_id": "..."?}}.
     */
    private Response ticketCreate(String requestBody) {
        return ticketCreate(requestBody, null);
    }

    /** Creates a ticket for a project-scoped board, rejecting a conflicting body project id. */
    private Response ticketCreate(String requestBody, String scopedProjectId) {
        Map<String, Object> req = parseObject(requestBody);
        String ticketNo = str(req, "ticket_no");
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "ticket_no is required");
        }
        String title = str(req, "title");
        if (title == null) {
            title = "";
        }
        if (tickets.find(ticketNo).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "ticket already exists: " + ticketNo);
        }
        String priority = parsePriority(req);
        String description = optionalText(req, "description");
        String note = optionalText(req, "note");
        List<String> labels = req.containsKey("labels") ? parseTicketLabels(req) : List.of();
        String requestedProjectId = str(req, "project_id");
        if (requestedProjectId != null && requestedProjectId.isBlank()) {
            requestedProjectId = null;
        }
        if (scopedProjectId != null && requestedProjectId != null
                && !scopedProjectId.equals(requestedProjectId)) {
            throw new GateException(GateErrorCode.USAGE,
                    "project_id does not match the project ticket board");
        }
        String projectId = scopedProjectId != null ? scopedProjectId : requestedProjectId;
        if (projectId != null && projectId.isBlank()) {
            projectId = null;
        }
        if (projectId != null && projects.find(projectId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such project: " + projectId);
        }
        String agentConfigId = str(req, "agent_config_id");
        if (agentConfigId != null && agentConfigId.isBlank()) {
            agentConfigId = null;
        }
        if (agentConfigId != null && agentConfigs.find(agentConfigId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + agentConfigId);
        }

        String targetRef = config.primaryTargetRef();
        RepoRef auth = RepoRef.of(config.authRepo());
        Path cloneDir = config.clonesRoot().resolve(ticketNo);
        RepoRef clone = topologyInitializer.createClone(auth, targetRef, cloneDir);
        Instant now = clock.now();
        tickets.insert(new Ticket(ticketNo, title, targetRef, clone.pathString(),
                null, null, "manual", "human", TicketStage.IN_PROGRESS, now, now,
                null, null, agentConfigId, priority, projectId, description, note, labels));
        Ticket created = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.GATE_ERROR_IO, "ticket was created but could not be reloaded: " + ticketNo));
        return new Response(201, ticketJson(created, projectNameIndex()));
    }

    private Project requireProject(String projectId) {
        return projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
    }

    private Ticket ticketInProject(String projectId, String ticketNo) {
        requireProject(projectId);
        Ticket ticket = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        if (!projectId.equals(ticket.projectId())) {
            throw new GateException(GateErrorCode.USAGE,
                    "ticket " + ticketNo + " does not belong to project " + projectId);
        }
        return ticket;
    }

    /**
     * V8 editable metadata: {@code {"title"?, "description"?, "note"?, "labels"?, "priority"?,
     * "stage"?, "agent_config_id"?}}. A present-but-null priority clears it; description/note may
     * also be cleared with null or an empty string, and labels are a complete replacement list.
     * {@code agent_config_id} rebinds the executing agent (null/blank detaches it, back to
     * manual). Stage changes are queue-management moves only — anything crossing the review gate
     * (PRESUBMITTED / IN_REVIEW / READY_TO_PUBLISH, in either direction) is refused; those
     * transitions belong to presubmit/review/publish.
     */
    private Response ticketUpdate(String ticketNo, String requestBody) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        return ticketUpdate(t, ticketNo, requestBody);
    }

    private Response ticketUpdate(String projectId, String ticketNo, String requestBody) {
        Ticket t = ticketInProject(projectId, ticketNo);
        return ticketUpdate(t, ticketNo, requestBody);
    }

    private Response ticketUpdate(Ticket t, String ticketNo, String requestBody) {
        Map<String, Object> req = parseObject(requestBody);
        boolean hasEditable = req.containsKey("title") || req.containsKey("description")
                || req.containsKey("note") || req.containsKey("labels") || req.containsKey("priority");
        boolean hasAgentConfig = req.containsKey("agent_config_id");
        if (!hasEditable && !hasAgentConfig && !req.containsKey("stage")) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide title, description, note, labels, priority, agent_config_id or stage");
        }
        String title = req.containsKey("title") ? str(req, "title") : null;
        if (title != null && title.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "title must not be blank");
        }
        String priority = req.containsKey("priority") ? parsePriority(req) : t.priority();
        String description = req.containsKey("description")
                ? optionalText(req, "description") : t.description();
        String note = req.containsKey("note") ? optionalText(req, "note") : t.note();
        List<String> labels = req.containsKey("labels") ? parseTicketLabels(req) : t.labels();
        String agentConfigId = null;
        if (hasAgentConfig) {
            agentConfigId = str(req, "agent_config_id");
            if (agentConfigId != null && agentConfigId.isBlank()) {
                agentConfigId = null;
            }
            if (agentConfigId != null && agentConfigs.find(agentConfigId).isEmpty()) {
                throw new GateException(GateErrorCode.USAGE, "no such agent config: " + agentConfigId);
            }
        }
        if (req.containsKey("stage")) {
            TicketStage next = parseStage(str(req, "stage"));
            ensureQueueTransition(t.stage(), next);
            tickets.updateStage(ticketNo, next, clock.now());
        }
        if (hasEditable) {
            tickets.updateEditable(ticketNo, title, priority, description, note, labels, clock.now());
        }
        if (hasAgentConfig) {
            tickets.updateAgentConfig(ticketNo, agentConfigId, clock.now());
        }
        return new Response(200, ticketJson(
                tickets.find(ticketNo).orElseThrow(() -> new GateException(
                        GateErrorCode.USAGE, "no such ticket: " + ticketNo)),
                projectNameIndex()));
    }

    /** P0..P3, or null when absent/present-but-null (explicit clear). */
    private static String parsePriority(Map<String, Object> req) {
        Object raw = req.get("priority");
        if (raw == null) {
            return null;
        }
        String priority = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (!Ticket.PRIORITIES.contains(priority)) {
            throw new GateException(GateErrorCode.USAGE,
                    "priority must be one of " + Ticket.PRIORITIES + " or null");
        }
        return priority;
    }

    /** Optional text field: null and blank values both clear the stored content. */
    private static String optionalText(Map<String, Object> req, String key) {
        Object raw = req.get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /** Ticket labels: a full replacement list, normalized and capped by the domain. */
    private static List<String> parseTicketLabels(Map<String, Object> req) {
        Object raw = req.get("labels");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "labels must be an array of strings");
        }
        List<String> labels = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                throw new GateException(GateErrorCode.USAGE, "label must not be null");
            }
            String label = item.toString().trim();
            if (label.isEmpty() || labels.contains(label)) {
                continue;
            }
            if (label.length() > Ticket.MAX_LABEL_LENGTH) {
                throw new GateException(GateErrorCode.USAGE,
                        "label longer than " + Ticket.MAX_LABEL_LENGTH + " chars");
            }
            labels.add(label);
        }
        if (labels.size() > Ticket.MAX_LABELS) {
            throw new GateException(GateErrorCode.USAGE,
                    "at most " + Ticket.MAX_LABELS + " labels");
        }
        return labels;
    }

    private static TicketStage parseStage(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "stage must not be blank");
        }
        try {
            return TicketStage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GateException(GateErrorCode.USAGE, "no such stage: " + raw);
        }
    }

    /** Queue moves the console may do directly; review-gated stages must go through the gate tools. */
    private static void ensureQueueTransition(TicketStage from, TicketStage to) {
        Set<TicketStage> reviewGated = Set.of(TicketStage.PRESUBMITTED, TicketStage.IN_REVIEW,
                TicketStage.READY_TO_PUBLISH);
        boolean toInProgress = to == TicketStage.IN_PROGRESS
                && !reviewGated.contains(from);
        boolean toCancelled = to == TicketStage.CANCELLED
                && !reviewGated.contains(from) && from != TicketStage.CANCELLED;
        if (from == to || toInProgress || toCancelled) {
            return;
        }
        throw new GateException(GateErrorCode.USAGE,
                "review-gated stage; use presubmit/review/publish endpoints (" + from + " -> " + to + ")");
    }

    private Map<String, String> projectNameIndex() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Project p : projects.findAll()) {
            names.put(p.id(), p.name());
        }
        return names;
    }

    private Map<String, Object> ticketJson(Ticket t) {
        return ticketJson(t, Map.of());
    }

    private static Map<String, Object> ticketJson(Ticket t, Map<String, String> projectNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket_no", t.ticketNo());
        m.put("title", t.title());
        m.put("target_ref", t.targetRef());
        m.put("clone_path", t.clonePath());
        m.put("stage", t.stage().name());
        m.put("reviewer_provider_id", t.reviewerProviderId());
        m.put("reviewer_model", t.reviewerModel());
        m.put("exec_token_total", t.execTokenTotal());
        m.put("exec_token_source", t.execTokenSource());
        m.put("agent_config_id", t.agentConfigId());
        m.put("priority", t.priority());
        m.put("project_id", t.projectId());
        m.put("project", t.projectId() == null ? null : projectNames.get(t.projectId()));
        m.put("description", t.description());
        m.put("note", t.note());
        m.put("labels", t.labels());
        m.put("created_at", t.createdAt() == null ? null : t.createdAt().toString());
        m.put("updated_at", t.updatedAt() == null ? null : t.updatedAt().toString());
        return m;
    }

    // --- presubmit (synchronous) ----------------------------------------------------------------

    private Response presubmit(String ticketNo) {
        try (AutoCloseable ignored = ticketLockManager.tryAcquire(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.REJECT_PRECONDITION,
                        "session in progress on this clone; presubmit refused while a session is active"))) {
            return presubmitLocked(ticketNo);
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "presubmit lock failed", e);
        }
    }

    private Response presubmitLocked(String ticketNo) {
        PresubmitResult r = gateService.presubmit(new PresubmitCommand(ticketNo));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", r.ticketNo());
        body.put("review_round", r.reviewRound());
        body.put("tree_hash", r.treeHash());
        body.put("base_commit", r.baseCommit());
        body.put("target_ref", r.targetRef());
        body.put("diff_bytes", r.diffBytes());
        body.put("changed_paths", r.changedPaths());
        body.put("integrity_warnings", r.integrity().warnings().stream()
                .map(v -> v.rule() + ":" + v.detail()).toList());
        return new Response(200, body);
    }

    private Response presubmitDiff(String ticketNo, String roundStr) {
        int round = parseIntOr(roundStr, -1);
        var row = (round < 0 ? presubmits.findLatest(ticketNo) : presubmits.find(ticketNo, round))
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no presubmit round for " + ticketNo + (round < 0 ? "" : "/" + round)));
        byte[] diff = blobStore.get(new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("review_round", row.reviewRound());
        body.put("tree_hash", row.treeHash().hex());
        body.put("base_commit", row.baseCommit().hex());
        body.put("diff", new String(diff, StandardCharsets.UTF_8));
        return new Response(200, body);
    }

    // --- review-result (reject feedback) --------------------------------------------------------

    private Response reviewResult(String ticketNo) {
        var presubmitRow = presubmits.findLatest(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no presubmit round for " + ticketNo));
        var reviewRow = reviewResults.findLatestForPresubmit(presubmitRow.id())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no review result for " + ticketNo + " round " + presubmitRow.reviewRound()));
        byte[] findingsBytes = blobStore.get(new BlobRef(reviewRow.findingsBlobPath(), 0, "0".repeat(64)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("review_round", presubmitRow.reviewRound());
        body.put("verdict", reviewRow.verdict().name());
        body.put("engine_id", reviewRow.engine().engineId());
        body.put("covered_ok", reviewRow.coveredOk());
        body.put("degraded", reviewRow.degraded());
        body.put("findings", new String(findingsBytes, StandardCharsets.UTF_8));
        return new Response(200, body);
    }

    // --- S2 async tasks: review / publish / task detail ------------------------------------------

    private Response review(String ticketNo, String requestBody) {
        Map<String, Object> req = parseObject(requestBody);
        Integer round = null;
        if (req.containsKey("round") && req.get("round") != null) {
            round = Integer.parseInt(req.get("round").toString());
        }
        boolean humanPass = req.containsKey("human_pass")
                && Boolean.parseBoolean(req.get("human_pass").toString());
        String note = str(req, "note");
        if (!config.engineConfigured() && !req.containsKey("human_pass")) {
            throw new GateException(GateErrorCode.USAGE,
                    "no engine configured: review requires body.human_pass (or configure engine.cmd in gate.toml)");
        }
        String taskId = taskRunner.submitReview(ticketNo, round, humanPass, note);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        return new Response(202, body);
    }

    private Response publish(String ticketNo, String requestBody) {
        Map<String, Object> req = parseObject(requestBody);
        Integer round = null;
        if (req.containsKey("round") && req.get("round") != null) {
            round = Integer.parseInt(req.get("round").toString());
        }
        String taskId = taskRunner.submitPublish(ticketNo, round);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        return new Response(202, body);
    }

    private Response taskDetail(String id) {
        GateTask t = taskRegistry.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such task: " + id));
        return new Response(200, taskJson(t));
    }

    private static Map<String, Object> taskJson(GateTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("type", t.type());
        m.put("ticket_no", t.ticketNo());
        m.put("session_id", t.sessionId());
        m.put("status", t.status().name());
        m.put("started_at", t.startedAt().toString());
        m.put("finished_at", t.finishedAt() == null ? null : t.finishedAt().toString());
        m.put("result_json", t.resultJson());
        m.put("error_json", t.errorJson());
        return m;
    }

    // --- S3 AgentConfig CRUD ---------------------------------------------------------------------

    private Response agentConfigList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentConfig c : agentConfigs.findAll()) {
            out.add(agentConfigJson(c));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_configs", out);
        return new Response(200, body);
    }

    private Response agentConfigDetail(String id) {
        AgentConfig c = agentConfigs.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such agent config: " + id));
        return new Response(200, agentConfigJson(c));
    }

    private Response agentConfigCreate(String requestBody) {
        AgentConfig c = parseAgentConfig(requestBody, null);
        if (agentConfigs.find(c.id()).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "agent config already exists: " + c.id());
        }
        agentConfigs.insert(c, clock.now());
        return new Response(201, agentConfigJson(c));
    }

    private Response agentConfigUpdate(String id, String requestBody) {
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        AgentConfig c = parseAgentConfig(requestBody, id);
        agentConfigs.update(c, clock.now());
        return new Response(200, agentConfigJson(agentConfigs.find(id).orElseThrow()));
    }

    private Response agentConfigDelete(String id) {
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        agentConfigs.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new Response(200, body);
    }

    private Response agentConfigSessions(String id) {
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessionRepository.findByAgentConfig(id)) {
            out.add(sessionJson(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        return new Response(200, body);
    }

    private static Map<String, Object> agentConfigJson(AgentConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("cli", c.cli().name());
        m.put("provider_id", c.providerId());
        m.put("model", c.model());
        m.put("system_prompt", c.systemPrompt());
        m.put("extra_flags", c.extraFlags());
        m.put("description", c.description());
        return m;
    }

    private static Map<String, Object> sessionJson(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("ticket_no", s.ticketNo());
        m.put("agent_config_id", s.agentConfigId());
        m.put("cli", s.cli().name());
        m.put("status", s.status().name());
        m.put("cli_session_id", s.cliSessionId());
        m.put("clone_path", s.clonePath());
        m.put("allocated_port", s.allocatedPort());
        m.put("started_at", s.startedAt().toString());
        m.put("finished_at", s.finishedAt() == null ? null : s.finishedAt().toString());
        if (s.cumulativeUsage() == null) {
            m.put("cumulative_usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", s.cumulativeUsage().promptTokens());
            u.put("completion_tokens", s.cumulativeUsage().completionTokens());
            u.put("total_tokens", s.cumulativeUsage().totalTokens());
            m.put("cumulative_usage", u);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static AgentConfig parseAgentConfig(String requestBody, String idOverride) {
        Map<String, Object> req = parseObject(requestBody);
        String id = idOverride != null ? idOverride : str(req, "id");
        if (id == null || id.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config id is required");
        }
        String name = str(req, "name");
        if (name == null || name.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config name is required");
        }
        String cli = str(req, "cli");
        if (cli == null || cli.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config cli is required");
        }
        String providerId = str(req, "provider_id");
        String model = str(req, "model");
        // A local CLI is the runtime owner. Provider/model are optional override coordinates and
        // are intentionally not inferred from the local API-provider registry.
        List<String> extraFlags = new ArrayList<>();
        Object flags = req.get("extra_flags");
        if (flags instanceof List<?> list) {
            for (Object o : list) {
                extraFlags.add(String.valueOf(o));
            }
        }
        return new AgentConfig(id, name, AgentCli.valueOf(cli.toUpperCase(java.util.Locale.ROOT)),
                providerId, model, str(req, "system_prompt"), extraFlags, str(req, "description"));
    }

    // --- S4 session routes -----------------------------------------------------------------------

    private Response ticketSessions(String ticketNo) {
        if (tickets.find(ticketNo).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessionRepository.findByTicket(ticketNo)) {
            out.add(sessionJson(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        return new Response(200, body);
    }

    private Response sessionCreate(String ticketNo, String requestBody) {
        gate.domain.ticket.Ticket ticket = tickets.find(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Map<String, Object> req = parseObject(requestBody);
        String agentConfigId = str(req, "agent_config_id");
        if (agentConfigId == null || agentConfigId.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent_config_id is required");
        }
        String initialPrompt = str(req, "initial_prompt");
        if (initialPrompt == null) {
            initialPrompt = "";
        }
        Session s = agentSessionPort.start(new AgentSessionPort.StartRequest(
                ticketNo, agentConfigId, ticket.clonePath(), ticket.targetRef(),
                initialPrompt, Map.of()));
        return new Response(201, sessionJson(s));
    }

    private Response sessionDetail(String sessionId) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        return new Response(200, sessionJson(s));
    }

    private Response sessionHistory(String sessionId) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (gate.domain.session.SessionMessage m : sessionRepository.findMessages(sessionId)) {
            out.add(sessionMessageJson(m));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", out);
        return new Response(200, body);
    }

    private Response sessionSend(String sessionId, String requestBody) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        Map<String, Object> req = parseObject(requestBody);
        String message = str(req, "message");
        if (message == null || message.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "message is required");
        }
        String taskId = agentSessionPort.sendMessage(new AgentSessionPort.SendRequest(sessionId, message, true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        return new Response(202, body);
    }

    private Response sessionAbort(String sessionId) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        agentSessionPort.abort(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new Response(200, body);
    }

    private static Map<String, Object> sessionMessageJson(gate.domain.session.SessionMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("session_id", m.sessionId());
        out.put("role", m.role().name());
        out.put("content", m.content());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (gate.domain.session.ToolCall tc : m.toolCalls()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", tc.name());
            cm.put("arguments_json", tc.argumentsJson());
            cm.put("result_json", tc.resultJson());
            calls.add(cm);
        }
        out.put("tool_calls", calls);
        if (m.usage() == null) {
            out.put("usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", m.usage().promptTokens());
            u.put("completion_tokens", m.usage().completionTokens());
            u.put("total_tokens", m.usage().totalTokens());
            out.put("usage", u);
        }
        out.put("degraded", m.degraded());
        out.put("timestamp", m.timestamp().toString());
        return out;
    }

    // --- reconcile ------------------------------------------------------------------------------

    private Response reconcile(String requestBody) {
        String ticketNo = null;
        if (requestBody != null && !requestBody.isBlank()) {
            ticketNo = str(parseObject(requestBody), "ticket_no");
        }
        ReconcileResult r = gateService.reconcile(new ReconcileCommand(ticketNo));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (ReconcileResult.IntentOutcome o : r.outcomes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent_id", o.intentId());
            m.put("ticket_no", o.ticketNo());
            m.put("review_round", o.reviewRound());
            m.put("tree_hash", o.treeHash());
            m.put("commit_sha", o.commitSha());
            m.put("from", o.from());
            m.put("to", o.to());
            m.put("reason", o.reason());
            outcomes.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcomes", outcomes);
        return new Response(200, body);
    }

    // --- metrics --------------------------------------------------------------------------------

    private Response metrics() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetricsService.MetricRecord r : metricsService.export()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket_no", r.ticketNo());
            m.put("review_round", r.reviewRound());
            m.put("verdict", r.verdict());
            m.put("diff_bytes", r.diffBytes());
            m.put("diff_lines", r.diffLines());
            m.put("prompt_tokens", r.promptTokens());
            m.put("completion_tokens", r.completionTokens());
            m.put("total_tokens", r.totalTokens());
            m.put("token_source", r.tokenSource());
            m.put("review_wall_ms", r.reviewWallMs());
            m.put("llm_wall_ms", r.llmWallMs());
            m.put("exec_token_total", r.execTokenTotal());
            m.put("exec_token_source", r.execTokenSource());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", rows);
        return new Response(200, body);
    }

    private Response metricsH1() {
        var v = metricsService.verdict();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("classification", v.classification());
        body.put("cost_ratio_median", Double.isNaN(v.costRatioMedian()) ? null : v.costRatioMedian());
        body.put("first_pass_rate", Double.isNaN(v.firstPassRate()) ? null : v.firstPassRate());
        body.put("sample_count", v.sampleCount());
        body.put("metric_basis", v.metricBasis());
        body.put("degradation_note", v.degradationNote());
        return new Response(200, body);
    }

    // --- config (redacted) / providers ----------------------------------------------------------

    private Response configView() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", config.project());
        body.put("auth_repo", config.authRepo().toString());
        body.put("clones_root", config.clonesRoot().toString());
        body.put("target_ref_whitelist", config.targetRefWhitelist());
        body.put("gate_home", config.gateHome().toString());
        body.put("engine_configured", config.engineConfigured());
        if (config.webConfigured()) {
            Map<String, Object> web = new LinkedHashMap<>();
            web.put("bind", config.web().bind());
            web.put("port", config.web().port());
            web.put("allowed_origins", config.web().allowedOrigins());
            body.put("web", web);
        }
        // Deliberately NO api_key / base_url secrets (脱敏, §4.1).
        return new Response(200, body);
    }

    private Response providerList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProviderRepository.ProviderRow p : providers.findAll()) {
            if (p.id().equals("cli-default")) {
                continue;
            }
            List<String> models = providers.models(p.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("base_url", p.baseUrl());
            m.put("type", p.type());
            m.put("credential_configured", p.apiKeyRef() != null && !p.apiKeyRef().isBlank());
            m.put("model_count", models.size());
            m.put("models", models);
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providers", rows);
        return new Response(200, body);
    }

    private Response providerCreate(String requestBody) {
        Map<String, Object> req = parseObject(requestBody);
        String id = required(req, "id");
        if (providers.find(id).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "provider already exists: " + id);
        }
        ProviderRepository.ProviderRow row = parseProvider(req, id, null);
        providers.upsert(row, clock.now());
        return providerDetail(id);
    }

    private Response providerUpdate(String id, String requestBody) {
        ProviderRepository.ProviderRow existing = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        ProviderRepository.ProviderRow row = parseProvider(parseObject(requestBody), id, existing);
        providers.upsert(row, clock.now());
        return providerDetail(id);
    }

    private Response providerDelete(String id) {
        if (providers.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such provider: " + id);
        }
        if ("manual".equals(id) || "cli-default".equals(id)) {
            throw new GateException(GateErrorCode.USAGE, "manual provider cannot be deleted");
        }
        providers.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new Response(200, body);
    }

    private Response providerModelsUpdate(String id, String requestBody) {
        if (providers.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such provider: " + id);
        }
        Map<String, Object> req = parseObject(requestBody);
        Object rawModels = req.get("models");
        if (!(rawModels instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "models must be an array");
        }
        List<String> models = new ArrayList<>();
        for (Object value : list) {
            String model = value == null ? "" : value.toString().trim();
            if (!model.isBlank() && !models.contains(model)) {
                models.add(model);
            }
        }
        providers.replaceModels(id, models, clock.now());
        return providerDetail(id);
    }

    private Response providerDetail(String id) {
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("name", p.name());
        body.put("base_url", p.baseUrl());
        body.put("type", p.type());
        body.put("credential_configured", p.apiKeyRef() != null && !p.apiKeyRef().isBlank());
        List<String> models = providers.models(id);
        body.put("model_count", models.size());
        body.put("models", models);
        return new Response(200, body);
    }

    private static ProviderRepository.ProviderRow parseProvider(Map<String, Object> req, String id,
            ProviderRepository.ProviderRow existing) {
        String name = required(req, "name");
        String baseUrl = required(req, "base_url");
        String type = required(req, "type");
        String apiKeyRef = str(req, "api_key_ref");
        if (apiKeyRef == null || apiKeyRef.isBlank()) {
            apiKeyRef = existing == null ? "unconfigured" : existing.apiKeyRef();
        }
        Instant createdAt = existing == null ? Instant.now() : existing.createdAt();
        return new ProviderRepository.ProviderRow(id, name, baseUrl, apiKeyRef, type, createdAt);
    }

    private static String required(Map<String, Object> req, String key) {
        String value = str(req, key);
        if (value == null || value.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, key + " is required");
        }
        return value.trim();
    }

    // --- V5: workspaces / projects / model fetch / working diff ---------------------------------

    /**
     * Lists the immediate subdirectories of a workspace candidate (codex-style picker).
     * Delegated to {@link gate.web.project.ProjectRoutes#workspaces(String)}.
     */
    private Response workspaces(String rawPath) {
        return projectRoutes.workspaces(rawPath);
    }

    private static Path normalizeWorkspace(String raw) {
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    /**
     * Delegated to {@link gate.web.project.ProjectRoutes#projectList()}.
     */
    private Response projectList() {
        return projectRoutes.projectList();
    }

    /**
     * Body: {@code {"name", "workspace_path", "init_git"?, "target_ref"?, "priority"?, "size"?,
     * "tags"?}}. Creates the directory when missing and optionally {@code git init}s it — the
     * console's "选择工作区 → 创建项目" flow.
     */
    private Response projectCreate(String requestBody) {
        Map<String, Object> req = parseObject(requestBody);
        String name = required(req, "name");
        String workspaceRaw = required(req, "workspace_path");
        Path workspace = normalizeWorkspace(workspaceRaw);
        if (!workspace.isAbsolute()) {
            throw new GateException(GateErrorCode.USAGE, "workspace_path must be absolute: " + workspaceRaw);
        }
        if (projects.findIdByWorkspacePath(workspace.toString()).isPresent()) {
            throw new GateException(GateErrorCode.USAGE,
                    "workspace already registered as a project: " + workspace);
        }
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot create workspace " + workspace + ": " + e.getMessage(), e);
        }
        boolean initGit = Boolean.parseBoolean(String.valueOf(req.getOrDefault("init_git", "false")));
        if (initGit && !Files.exists(workspace.resolve(".git"))) {
            gate.ports.ProcessRunner.ProcRun r = git.run(workspace, Map.of(), "init", "-b", "main");
            if (!r.ok()) {
                // Older git without -b: retry with the default branch name.
                r = git.run(workspace, Map.of(), "init");
            }
            if (!r.ok()) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "git init failed in " + workspace + ": " + r.stderrFirstLine());
            }
        }
        String targetRef = str(req, "target_ref");
        String priority = parsePriority(req);
        String size = parseProjectSize(req);
        List<String> tags = parseProjectTags(req);
        Instant now = clock.now();
        Project p = new Project(uniqueProjectId(name), name, workspace.toString(),
                targetRef, config.authRepo().toString(), priority, size, tags, now, now);
        projects.insert(p);
        return new Response(201, projectJson(p));
    }

    /**
     * Body: {@code {"name"?, "workspace_path"?, "priority"?, "size"?, "tags"?}}. Present-but-null
     * priority/size clears the value; an absent key keeps it. {@code tags} is a full replacement
     * list (null or [] clears all).
     */
    private Response projectUpdate(String id, String requestBody) {
        Project existing = projects.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + id));
        Map<String, Object> req = parseObject(requestBody);
        if (!req.containsKey("name") && !req.containsKey("workspace_path")
                && !req.containsKey("priority") && !req.containsKey("size") && !req.containsKey("tags")) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide name, workspace_path, priority, size or tags");
        }
        String name = req.containsKey("name") ? required(req, "name") : existing.name();
        String workspace = existing.workspacePath();
        if (req.containsKey("workspace_path")) {
            workspace = normalizeWorkspace(required(req, "workspace_path")).toString();
            var other = projects.findIdByWorkspacePath(workspace);
            if (other.isPresent() && !other.get().equals(id)) {
                throw new GateException(GateErrorCode.USAGE,
                        "workspace already registered as a project: " + workspace);
            }
        }
        String priority = req.containsKey("priority") ? parsePriority(req) : existing.priority();
        String size = req.containsKey("size") ? parseProjectSize(req) : existing.size();
        List<String> tags = req.containsKey("tags") ? parseProjectTags(req) : existing.tags();
        Project updated = new Project(id, name, workspace, existing.targetRef(), existing.authRepo(),
                priority, size, tags, existing.createdAt(), clock.now());
        projects.update(updated);
        return new Response(200, projectJson(updated));
    }

    /** Project size bucket: small | medium | large, or null when absent/present-but-null. */
    private static String parseProjectSize(Map<String, Object> req) {
        Object raw = req.get("size");
        if (raw == null) {
            return null;
        }
        String size = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (!Project.SIZES.contains(size)) {
            throw new GateException(GateErrorCode.USAGE,
                    "size must be one of " + Project.SIZES + " or null");
        }
        return size;
    }

    /** Tags: array of strings, trimmed, blanks dropped, de-duplicated, capped by the domain. */
    private static List<String> parseProjectTags(Map<String, Object> req) {
        Object raw = req.get("tags");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "tags must be an array of strings");
        }
        List<String> tags = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                throw new GateException(GateErrorCode.USAGE, "tag must not be null");
            }
            String tag = item.toString().trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        if (tags.size() > Project.MAX_TAGS) {
            throw new GateException(GateErrorCode.USAGE, "at most " + Project.MAX_TAGS + " tags");
        }
        return tags;
    }

    private Response projectDelete(String id) {
        if (projects.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such project: " + id);
        }
        tickets.clearProject(id, clock.now());
        projects.deleteById(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new Response(200, body);
    }

    private static Map<String, Object> projectJson(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("workspace_path", p.workspacePath());
        m.put("target_ref", p.targetRef());
        m.put("auth_repo", p.authRepo());
        m.put("priority", p.priority());
        m.put("size", p.size());
        m.put("tags", p.tags());
        m.put("ticket_count", 0);
        m.put("active_ticket_count", 0);
        m.put("created_at", p.createdAt().toString());
        m.put("updated_at", p.updatedAt().toString());
        return m;
    }

    /** Slugified id from the name ("My App!" → "my-app"), de-duplicated with a numeric suffix. */
    private String uniqueProjectId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) {
            base = "project";
        }
        String id = base;
        int suffix = 2;
        while (projects.find(id).isPresent()) {
            id = base + "-" + suffix++;
        }
        return id;
    }

    /**
     * V5: pulls the provider's model list from its upstream {@code /models} endpoint and persists
     * it (an explicit action, matching the {@code model} table's contract — never implicit).
     */
    private Response providerModelsFetch(String id) {
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        if ("manual".equals(id)) {
            throw new GateException(GateErrorCode.USAGE, "manual provider has no upstream");
        }
        List<String> models = modelFetcher.fetch(p);
        providers.replaceModels(id, models, clock.now());
        return providerDetail(id);
    }

    /**
     * V5: live working-tree diff of the ticket clone — {@code git diff HEAD} plus untracked files
     * synthesized as new-file hunks. This is what the review console shows before the first
     * presubmit round exists (previously the UI fell back to a hardcoded sample diff).
     */
    private Response workingDiff(String ticketNo) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Path clone = Path.of(t.clonePath());
        if (!Files.isDirectory(clone)) {
            throw new GateException(GateErrorCode.USAGE, "clone not found: " + t.clonePath());
        }
        gate.ports.ProcessRunner.ProcRun head = git.run(clone, Map.of(), "rev-parse", "HEAD");
        String baseCommit = head.ok() ? head.stdout().trim() : null;

        StringBuilder diff = new StringBuilder();
        gate.ports.ProcessRunner.ProcRun tracked = baseCommit == null
                ? git.run(clone, Map.of(), "diff")
                : git.run(clone, Map.of(), "diff", "HEAD");
        if (tracked.ok() && !tracked.stdout().isBlank()) {
            diff.append(tracked.stdout().stripTrailing()).append('\n');
        }
        gate.ports.ProcessRunner.ProcRun untracked = git.run(clone, Map.of(), "ls-files", "--others", "--exclude-standard");
        if (untracked.ok()) {
            for (String file : untracked.stdout().split("\n")) {
                String rel = file.trim();
                if (rel.isEmpty()) {
                    continue;
                }
                appendNewFileDiff(diff, clone, rel);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("source", "working");
        body.put("base_commit", baseCommit);
        body.put("diff", diff.toString());
        return new Response(200, body);
    }

    /** Synthesizes a {@code new file} hunk for one untracked file; binary files get a header only. */
    private static void appendNewFileDiff(StringBuilder out, Path clone, String rel) {
        Path file = clone.resolve(rel);
        out.append("diff --git a/").append(rel).append(" b/").append(rel).append('\n');
        out.append("new file mode 100644\n");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            out.append("--- unreadable\n");
            return;
        }
        boolean binary = false;
        for (byte b : bytes.length > 8192 ? java.util.Arrays.copyOf(bytes, 8192) : bytes) {
            if (b == 0) {
                binary = true;
                break;
            }
        }
        if (binary) {
            out.append("Binary file ").append(rel).append(" differs\n");
            return;
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = java.util.Arrays.copyOf(lines, lines.length - 1); // drop the trailing empty split
        }
        out.append("--- /dev/null\n+++ b/").append(rel).append('\n');
        out.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            out.append('+').append(line).append('\n');
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? new String[0] : p.split("/");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = gate.application.MiniJson.parse(body.trim());
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (Exception e) {
            throw new GateException(GateErrorCode.USAGE, "malformed JSON body");
        }
        throw new GateException(GateErrorCode.USAGE, "request body must be a JSON object");
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
