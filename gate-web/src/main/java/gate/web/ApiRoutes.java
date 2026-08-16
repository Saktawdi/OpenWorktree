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
import gate.ports.ProviderRepository;
import gate.ports.ReviewResultRepository;
import gate.ports.SessionRepository;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
final class ApiRoutes {

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
    }

    /** A resolved response: HTTP status + a JSON-serialisable body. */
    record Response(int status, Object body) {
    }

    /**
     * Routes an authorised {@code /api/*} request. Throws {@link GateException} for gate-level
     * failures ({@link ApiHandler} maps to HTTP); returns a 404 {@link Response} for unknown paths.
     */
    Response route(String method, String path, String requestBody) {
        String[] seg = split(path);
        // seg[0] == "api"

        if (seg.length == 2 && seg[1].equals("status") && method.equals("GET")) {
            return status();
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (Ticket t : tickets.findAll()) {
            out.add(ticketJson(t));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tickets", out);
        return new Response(200, body);
    }

    private Response ticketDetail(String ticketNo) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        return new Response(200, ticketJson(t));
    }

    /**
     * Web takes over clone generation (D3, §4.2): mirrors {@code TicketCommand.Create} exactly, only
     * the driver changes from picocli to HTTP. Body: {@code {"ticket_no": "...", "title": "...",
     * "agent_config_id": "..."?}}.
     */
    private Response ticketCreate(String requestBody) {
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

        String targetRef = config.primaryTargetRef();
        RepoRef auth = RepoRef.of(config.authRepo());
        Path cloneDir = config.clonesRoot().resolve(ticketNo);
        RepoRef clone = topologyInitializer.createClone(auth, targetRef, cloneDir);
        Instant now = clock.now();
        tickets.insert(new Ticket(ticketNo, title, targetRef, clone.pathString(),
                null, null, "manual", "human", TicketStage.IN_PROGRESS, now, now));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("target_ref", targetRef);
        body.put("clone_path", clone.pathString());
        body.put("stage", TicketStage.IN_PROGRESS.name());
        return new Response(201, body);
    }

    private Map<String, Object> ticketJson(Ticket t) {
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
        if (providerId == null || providerId.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config provider_id is required");
        }
        String model = str(req, "model");
        if (model == null || model.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config model is required");
        }
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
        if ("manual".equals(id)) {
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
