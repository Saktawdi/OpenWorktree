package gate.web;

import gate.application.GateService;
import gate.application.MetricsService;
import gate.application.PresubmitCommand;
import gate.application.PresubmitResult;
import gate.application.ReconcileCommand;
import gate.application.ReconcileResult;
import gate.domain.blob.BlobRef;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.task.GateTask;
import gate.ports.BlobStore;
import gate.ports.PresubmitRepository;
import gate.ports.ProjectRepository;
import gate.ports.ProviderRepository;
import gate.ports.ReviewResultRepository;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import gate.domain.git.RepoRef;
import gate.adapters.git.GitCli;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Main API Route Registrar using Javalin.
 */
public final class ApiRoutes {

    private static final int EOL_NOISE_THRESHOLD_CHARS = 10_000;

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
    private final TicketLockManager ticketLockManager;
    private final ProjectRepository projects;
    private final ProviderModelFetcher modelFetcher;
    private final GitCli git;
    private final StatusRoutes statusRoutes;
    private final gate.web.project.ProjectRoutes projectRoutes;
    private final gate.web.project.RepoViewRoutes repoViewRoutes;
    private final gate.web.ticket.TicketRoutes ticketRoutes;
    private final gate.web.session.SessionRoutes sessionRoutes;
    private final gate.ports.SessionRepository sessions;
    private final SettingsRoutes settingsRoutes;
    private final SseHandler sseHandler;
    private final SessionSseHandler sessionSseHandler;

    public record Response(int status, Object body) {
    }

    public ApiRoutes(WebComponents c) {
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
        this.ticketLockManager = c.ticketLockManager();
        this.projects = c.projectRepository();
        this.modelFetcher = c.modelFetcher();
        this.git = c.git();
        this.statusRoutes = new StatusRoutes(gateService, config, c.runtimeInfo());
        this.projectRoutes = new gate.web.project.ProjectRoutes(projects, tickets, topologyInitializer,
                config, c.workspaceSyncer(), git);
        this.repoViewRoutes = new gate.web.project.RepoViewRoutes(projects, git);
        this.ticketRoutes = new gate.web.ticket.TicketRoutes(tickets, projects, c.agentConfigRepository(),
                topologyInitializer, config, clock);
        this.sessionRoutes = new gate.web.session.SessionRoutes(c.agentConfigRepository(),
                c.sessionRepository(), c.agentSessionPort(), tickets, clock,
                new gate.web.session.SessionModelCatalog(), c.credentials());
        this.sessions = c.sessionRepository();
        this.settingsRoutes = new SettingsRoutes(c.gateToml());
        this.sseHandler = new SseHandler(c.taskRegistry());
        this.sessionSseHandler = new SessionSseHandler(c.agentSessionPort(), c.sessionRepository());
    }

    public void register(Javalin app) {
        // Status & Runtime
        app.get("/api/status", ctx -> respond(ctx, statusRoutes.status()));
        app.get("/api/runtime", ctx -> respond(ctx, statusRoutes.runtime()));
        app.get("/api/agent-runtimes", ctx -> respond(ctx, statusRoutes.agentRuntimes()));
        app.get("/api/config", ctx -> respond(ctx, configView()));

        // Workspaces & Projects
        app.get("/api/workspaces", ctx -> respond(ctx, projectRoutes.workspaces(null)));
        app.post("/api/workspaces", ctx -> respond(ctx, projectRoutes.workspaces(str(Json.parseObject(ctx.body()), "path"))));

        app.get("/api/projects", ctx -> respond(ctx, projectRoutes.projectList()));
        app.post("/api/projects", ctx -> respond(ctx, projectCreate(ctx.body())));
        app.put("/api/projects/{id}", ctx -> respond(ctx, projectUpdate(ctx.pathParam("id"), ctx.body())));
        app.delete("/api/projects/{id}", ctx -> respond(ctx, projectDelete(ctx.pathParam("id"))));
        app.post("/api/projects/{id}/workspace-sync", ctx -> respond(ctx, projectRoutes.workspaceSync(ctx.pathParam("id"))));

        // Project Repo & Tree View
        app.get("/api/projects/{id}/repo", ctx -> respond(ctx, repoViewRoutes.repoView(ctx.pathParam("id"))));
        app.get("/api/projects/{id}/tree", ctx -> respond(ctx, repoViewRoutes.treeView(ctx.pathParam("id"), List.of())));
        app.get("/api/projects/{id}/tree/<path>", ctx -> {
            String pathParam = ctx.pathParam("path");
            String[] segments = pathParam.split("/");
            respond(ctx, repoViewRoutes.treeView(ctx.pathParam("id"), List.of(segments)));
        });

        // Project-scoped Tickets
        app.get("/api/projects/{projectId}/tickets", ctx -> {
            ticketRoutes.requireProject(ctx.pathParam("projectId"));
            respond(ctx, ticketRoutes.ticketList(ctx.pathParam("projectId")));
        });
        app.post("/api/projects/{projectId}/tickets", ctx -> {
            ticketRoutes.requireProject(ctx.pathParam("projectId"));
            respond(ctx, ticketRoutes.ticketCreate(ctx.body(), ctx.pathParam("projectId")));
        });
        app.get("/api/projects/{projectId}/tickets/{ticketNo}", ctx ->
                respond(ctx, ticketRoutes.ticketDetail(ctx.pathParam("projectId"), ctx.pathParam("ticketNo"))));
        app.patch("/api/projects/{projectId}/tickets/{ticketNo}", ctx ->
                respond(ctx, ticketRoutes.ticketUpdate(ctx.pathParam("projectId"), ctx.pathParam("ticketNo"), ctx.body())));
        app.put("/api/projects/{projectId}/tickets/{ticketNo}", ctx ->
                respond(ctx, ticketRoutes.ticketUpdate(ctx.pathParam("projectId"), ctx.pathParam("ticketNo"), ctx.body())));

        // Settings & MCP
        app.get("/api/settings/gate-toml", ctx -> respond(ctx, settingsRoutes.gateTomlView()));
        app.put("/api/settings/gate-toml", ctx -> respond(ctx, settingsRoutes.gateTomlUpdate(Json.parseObject(ctx.body()))));
        app.get("/api/mcp/status", ctx -> respond(ctx, settingsRoutes.mcpStatus()));

        // Providers
        app.get("/api/providers", ctx -> respond(ctx, providerList()));
        app.post("/api/providers", ctx -> respond(ctx, providerCreate(ctx.body())));
        app.put("/api/providers/{id}", ctx -> respond(ctx, providerUpdate(ctx.pathParam("id"), ctx.body())));
        app.delete("/api/providers/{id}", ctx -> respond(ctx, providerDelete(ctx.pathParam("id"))));
        app.put("/api/providers/{id}/models", ctx -> respond(ctx, providerModelsUpdate(ctx.pathParam("id"), ctx.body())));
        app.post("/api/providers/{id}/models/fetch", ctx -> respond(ctx, providerModelsFetch(ctx.pathParam("id"))));

        // Reconcile & Metrics
        app.post("/api/reconcile", ctx -> respond(ctx, reconcile(ctx.body())));
        app.get("/api/metrics", ctx -> respond(ctx, metrics()));
        app.get("/api/metrics/h1", ctx -> respond(ctx, metricsH1()));

        // Tickets (Global)
        app.get("/api/tickets", ctx -> respond(ctx, ticketRoutes.ticketList()));
        app.post("/api/tickets", ctx -> respond(ctx, ticketRoutes.ticketCreate(ctx.body())));
        app.get("/api/tickets/{ticketNo}", ctx -> respond(ctx, ticketRoutes.ticketDetail(ctx.pathParam("ticketNo"))));
        app.patch("/api/tickets/{ticketNo}", ctx ->
                respond(ctx, ticketRoutes.ticketUpdate(ctx.pathParam("ticketNo"), ctx.body())));
        app.put("/api/tickets/{ticketNo}", ctx ->
                respond(ctx, ticketRoutes.ticketUpdate(ctx.pathParam("ticketNo"), ctx.body())));
        app.get("/api/tickets/{ticketNo}/diff", ctx -> respond(ctx, workingDiff(ctx.pathParam("ticketNo"))));
        app.post("/api/tickets/{ticketNo}/presubmit", ctx -> respond(ctx, presubmit(ctx.pathParam("ticketNo"))));
        app.get("/api/tickets/{ticketNo}/review-result", ctx -> respond(ctx, reviewResult(ctx.pathParam("ticketNo"))));
        app.get("/api/tickets/{ticketNo}/presubmits", ctx -> respond(ctx, presubmitList(ctx.pathParam("ticketNo"))));
        app.get("/api/tickets/{ticketNo}/presubmit/{round}/diff", ctx ->
                respond(ctx, presubmitDiff(ctx.pathParam("ticketNo"), ctx.pathParam("round"))));

        // Tasks (Async review / publish)
        app.post("/api/tickets/{ticketNo}/review", ctx -> respond(ctx, review(ctx.pathParam("ticketNo"), ctx.body())));
        app.post("/api/tickets/{ticketNo}/publish", ctx -> respond(ctx, publish(ctx.pathParam("ticketNo"), ctx.body())));
        app.get("/api/tasks/{id}", ctx -> respond(ctx, taskDetail(ctx.pathParam("id"))));

        // Agent Configs
        app.get("/api/agent-configs", ctx -> respond(ctx, sessionRoutes.agentConfigList()));
        app.post("/api/agent-configs", ctx -> respond(ctx, sessionRoutes.agentConfigCreate(ctx.body())));
        app.get("/api/agent-configs/{id}", ctx -> respond(ctx, sessionRoutes.agentConfigDetail(ctx.pathParam("id"))));
        app.put("/api/agent-configs/{id}", ctx -> respond(ctx, sessionRoutes.agentConfigUpdate(ctx.pathParam("id"), ctx.body())));
        app.delete("/api/agent-configs/{id}", ctx -> respond(ctx, sessionRoutes.agentConfigDelete(ctx.pathParam("id"))));
        app.get("/api/agent-configs/{id}/sessions", ctx -> respond(ctx, sessionRoutes.agentConfigSessions(ctx.pathParam("id"))));

        // Sessions
        app.get("/api/tickets/{ticketNo}/sessions", ctx -> respond(ctx, sessionRoutes.ticketSessions(ctx.pathParam("ticketNo"))));
        app.post("/api/tickets/{ticketNo}/sessions", ctx -> respond(ctx, sessionRoutes.sessionCreate(ctx.pathParam("ticketNo"), ctx.body())));
        app.get("/api/sessions/{id}", ctx -> respond(ctx, sessionRoutes.sessionDetail(ctx.pathParam("id"))));
        app.patch("/api/sessions/{id}", ctx ->
                respond(ctx, sessionRoutes.sessionPatch(ctx.pathParam("id"), ctx.body())));
        app.delete("/api/sessions/{id}", ctx -> respond(ctx, sessionRoutes.sessionDelete(ctx.pathParam("id"))));
        app.get("/api/sessions/{id}/messages", ctx -> respond(ctx, sessionRoutes.sessionHistory(ctx.pathParam("id"))));
        app.post("/api/sessions/{id}/messages", ctx -> respond(ctx, sessionRoutes.sessionSend(ctx.pathParam("id"), ctx.body())));
        app.post("/api/sessions/{id}/abort", ctx -> respond(ctx, sessionRoutes.sessionAbort(ctx.pathParam("id"))));
        app.post("/api/sessions/{id}/model", ctx -> respond(ctx, sessionRoutes.sessionModelSet(ctx.pathParam("id"), ctx.body())));
        app.get("/api/sessions/{id}/models", ctx -> respond(ctx, sessionRoutes.sessionModels(ctx.pathParam("id"))));
        app.get("/api/sessions/{id}/permissions", ctx -> respond(ctx, sessionRoutes.permissionList(ctx.pathParam("id"))));
        app.post("/api/sessions/{id}/permissions/{permissionId}", ctx ->
                respond(ctx, sessionRoutes.sessionPermissionRespond(ctx.pathParam("id"), ctx.pathParam("permissionId"), ctx.body())));
        app.get("/api/agents/busy", ctx -> respond(ctx, sessionRoutes.agentsBusy()));

        // SSE endpoints (handle both standard SSE and requests without strict Accept header)
        app.get("/api/tasks/{id}/events", ctx -> {
            String id = ctx.pathParam("id");
            if (taskRegistry.find(id).isEmpty()) {
                ctx.status(io.javalin.http.HttpStatus.NOT_FOUND);
                ctx.contentType("application/json; charset=utf-8");
                ctx.result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND", "no such task: " + id, null));
                return;
            }
            startSse(ctx, client -> sseHandler.handle(client, id));
        });

        app.get("/api/sessions/{id}/events", ctx -> {
            String id = ctx.pathParam("id");
            if (sessions.find(id).isEmpty()) {
                ctx.status(io.javalin.http.HttpStatus.NOT_FOUND);
                ctx.contentType("application/json; charset=utf-8");
                ctx.result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND", "no such session: " + id, null));
                return;
            }
            startSse(ctx, client -> sessionSseHandler.handle(client, id));
        });
    }

    private static void startSse(Context ctx, java.util.function.Consumer<SseClient> consumer) {
        ctx.res().setStatus(200);
        ctx.res().setCharacterEncoding("UTF-8");
        ctx.res().setContentType("text/event-stream");
        ctx.res().addHeader("Connection", "close");
        ctx.res().addHeader("Cache-Control", "no-cache");
        ctx.res().addHeader("X-Accel-Buffering", "no");
        try {
            ctx.res().flushBuffer();
        } catch (IOException ignored) {
        }
        SseClient client = new SseClient(ctx);
        consumer.accept(client);
    }

    private static void respond(Context ctx, Response res) {
        ctx.status(res.status());
        ctx.json(res.body());
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

    private Response presubmitList(String ticketNo) {
        if (tickets.find(ticketNo).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : presubmits.findAllByTicket(ticketNo)) {
            String diff = new String(blobStore.get(
                    new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256())),
                    StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("review_round", row.reviewRound());
            m.put("tree_hash", row.treeHash().hex());
            m.put("base_commit", row.baseCommit().hex());
            m.put("target_ref", row.targetRef());
            m.put("diff_bytes", row.diffBytes());
            m.put("changed_count", countOccurrences(diff, "diff --git a/"));
            m.put("created_at", row.createdAt().toString());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("presubmits", rows);
        return new Response(200, body);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    // --- review-result --------------------------------------------------------------------------

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

    // --- S2 async tasks -------------------------------------------------------------------------

    private Response review(String ticketNo, String requestBody) {
        Map<String, Object> req = Json.parseObject(requestBody);
        Integer round = null;
        if (req.containsKey("round") && req.get("round") != null) {
            round = Integer.parseInt(req.get("round").toString());
        }
        Boolean humanPass = req.get("human_pass") == null ? null
                : Boolean.parseBoolean(req.get("human_pass").toString());
        String note = str(req, "note");
        String taskId = taskRunner.submitReview(ticketNo, round, humanPass, note);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        return new Response(202, body);
    }

    private Response publish(String ticketNo, String requestBody) {
        Map<String, Object> req = Json.parseObject(requestBody);
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

    // --- reconcile & metrics --------------------------------------------------------------------

    private Response reconcile(String requestBody) {
        String ticketNo = null;
        if (requestBody != null && !requestBody.isBlank()) {
            ticketNo = str(Json.parseObject(requestBody), "ticket_no");
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

    // --- config / providers ---------------------------------------------------------------------

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
        Map<String, Object> req = Json.parseObject(requestBody);
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
        ProviderRepository.ProviderRow row = parseProvider(Json.parseObject(requestBody), id, existing);
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
        Map<String, Object> req = Json.parseObject(requestBody);
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

    // --- V5: projects / working diff ------------------------------------------------------------

    private static Path normalizeWorkspace(String raw) {
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    private Response projectCreate(String requestBody) {
        Map<String, Object> req = Json.parseObject(requestBody);
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
                r = git.run(workspace, Map.of(), "init");
            }
            if (!r.ok()) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "git init failed in " + workspace + ": " + r.stderrFirstLine());
            }
        }
        String targetRef = str(req, "target_ref");
        String priority = gate.web.ticket.TicketRoutes.parsePriority(req);
        String size = parseProjectSize(req);
        List<String> tags = parseProjectTags(req);
        Instant now = clock.now();
        String id = uniqueProjectId(name);
        java.nio.file.Path projectAuthRepo = new gate.application.project.ProjectAuthResolver(projects, config)
                .defaultProjectAuthRepo(id);
        String effectiveTargetRef = effectiveTargetRef(targetRef);
        topologyInitializer.initAuthRepo(RepoRef.of(projectAuthRepo), effectiveTargetRef,
                config.approvalsDir());
        Project p = new Project(id, name, workspace.toString(),
                effectiveTargetRef, projectAuthRepo.toString(), priority, size, tags, now, now);
        projects.insert(p);
        return new Response(201, projectJson(p));
    }

    private Response projectUpdate(String id, String requestBody) {
        Project existing = projects.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + id));
        Map<String, Object> req = Json.parseObject(requestBody);
        if (!req.containsKey("name") && !req.containsKey("workspace_path")
                && !req.containsKey("target_ref") && !req.containsKey("priority")
                && !req.containsKey("size") && !req.containsKey("tags")) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide name, workspace_path, target_ref, priority, size or tags");
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
        String targetRef = req.containsKey("target_ref")
                ? effectiveTargetRef(str(req, "target_ref")) : effectiveTargetRef(existing.targetRef());
        String priority = req.containsKey("priority") ? gate.web.ticket.TicketRoutes.parsePriority(req) : existing.priority();
        String size = req.containsKey("size") ? parseProjectSize(req) : existing.size();
        List<String> tags = parseProjectTags(req);
        Project updated = new Project(id, name, workspace, targetRef, existing.authRepo(),
                priority, size, tags, existing.createdAt(), clock.now());
        projects.update(updated);
        return new Response(200, projectJson(updated));
    }

    private String effectiveTargetRef(String targetRef) {
        return targetRef == null || targetRef.isBlank() ? config.primaryTargetRef() : targetRef;
    }

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

    private Map<String, Object> projectJson(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("workspace_path", p.workspacePath());
        m.put("target_ref", p.targetRef());
        m.put("auth_repo", p.authRepo());
        m.put("priority", p.priority());
        m.put("size", p.size());
        m.put("tags", p.tags());
        List<Ticket> projectTickets = tickets.findAllByProject(p.id());
        m.put("ticket_count", projectTickets.size());
        m.put("active_ticket_count", (int) projectTickets.stream()
                .filter(t -> t.stage() != null && !t.stage().isTerminal()).count());
        m.put("created_at", p.createdAt().toString());
        m.put("updated_at", p.updatedAt().toString());
        return m;
    }

    private String uniqueProjectId(String name) {
        String base = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "project";
        }
        String candidate = base;
        int seq = 1;
        while (projects.find(candidate).isPresent()) {
            candidate = base + "-" + (++seq);
        }
        return candidate;
    }

    private Response workingDiff(String ticketNo) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Path clone = Path.of(t.clonePath());
        if (!Files.isDirectory(clone)) {
            throw new GateException(GateErrorCode.USAGE,
                    "clone directory does not exist for " + ticketNo + ": " + clone);
        }
        String baseCommit = null;
        var latestPresubmit = presubmits.findLatest(ticketNo);
        if (latestPresubmit.isPresent()) {
            baseCommit = latestPresubmit.get().baseCommit().hex();
        }
        gate.ports.ProcessRunner.ProcRun tracked = baseCommit == null
                ? git.run(clone, Map.of(), "diff")
                : git.run(clone, Map.of(), "diff", "HEAD");
        String trackedDiff = tracked.ok() ? tracked.stdout() : "";

        String eolWarning = null;
        if (trackedDiff.length() > EOL_NOISE_THRESHOLD_CHARS) {
            gate.ports.ProcessRunner.ProcRun normalized = baseCommit == null
                    ? git.run(clone, Map.of(), "diff", "--ignore-cr-at-eol")
                    : git.run(clone, Map.of(), "diff", "HEAD", "--ignore-cr-at-eol");
            if (normalized.ok() && normalized.stdout().length() * 10 < trackedDiff.length()) {
                eolWarning = "已忽略大量仅换行符（CRLF/LF）差异：该 clone 的检出未在 core.autocrlf=false 下进行，"
                        + "建议重建工作区；以下仅显示真实的内容变更。";
                trackedDiff = normalized.stdout();
            }
        }

        StringBuilder diff = new StringBuilder();
        if (!trackedDiff.isBlank()) {
            diff.append(trackedDiff.stripTrailing()).append('\n');
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
        if (eolWarning != null) {
            body.put("eol_warning", eolWarning);
        }
        return new Response(200, body);
    }

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
            lines = java.util.Arrays.copyOf(lines, lines.length - 1);
        }
        out.append("--- /dev/null\n+++ b/").append(rel).append('\n');
        out.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            out.append('+').append(line).append('\n');
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }

    private static int parseIntOr(String str, int defaultVal) {
        if (str == null || str.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
