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
    private final TicketLockManager ticketLockManager;
    private final ProjectRepository projects;
    private final ProviderModelFetcher modelFetcher;
    private final GitCli git;
    private final StatusRoutes statusRoutes;
    private final gate.web.project.ProjectRoutes projectRoutes;
    private final gate.web.project.RepoViewRoutes repoViewRoutes;
    private final gate.web.ticket.TicketRoutes ticketRoutes;
    private final gate.web.session.SessionRoutes sessionRoutes;

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
        // 发布后工作区同步（存量补同步 / DEFERRED 重试入口）：权威库目标分支 tip 尽力快进回写工作区。
        if (seg.length == 4 && seg[1].equals("projects") && seg[3].equals("workspace-sync")
                && method.equals("POST")) {
            return projectRoutes.workspaceSync(seg[2]);
        }
        // 项目 → 仓库视图: branch/commit graph + lazy file tree of the workspace repo.
        if (seg.length == 4 && seg[1].equals("projects") && seg[3].equals("repo")
                && method.equals("GET")) {
            return repoViewRoutes.repoView(seg[2]);
        }
        if (seg.length == 4 && seg[1].equals("projects") && seg[3].equals("tree")
                && method.equals("GET")) {
            return repoViewRoutes.treeView(seg[2], List.of());
        }
        if (seg.length >= 5 && seg[1].equals("projects") && seg[3].equals("tree")
                && method.equals("GET")) {
            return repoViewRoutes.treeView(seg[2],
                    List.of(java.util.Arrays.copyOfRange(seg, 4, seg.length)));
        }
        // Project-scoped ticket board: one project owns one board with N tickets.
        if (seg.length == 4 && seg[1].equals("projects") && seg[3].equals("tickets")) {            ticketRoutes.requireProject(seg[2]);
            if (method.equals("GET")) {
                return ticketRoutes.ticketList(seg[2]);
            }
            if (method.equals("POST")) {
                return ticketRoutes.ticketCreate(requestBody, seg[2]);
            }
        }
        if (seg.length == 5 && seg[1].equals("projects") && seg[3].equals("tickets")
                && method.equals("GET")) {
            return ticketRoutes.ticketDetail(seg[2], seg[4]);
        }
        if (seg.length == 5 && seg[1].equals("projects") && seg[3].equals("tickets")
                && (method.equals("PATCH") || method.equals("PUT"))) {
            return ticketRoutes.ticketUpdate(seg[2], seg[4], requestBody);
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
                return ticketRoutes.ticketList();
            }
            if (method.equals("POST")) {
                return ticketRoutes.ticketCreate(requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("tickets") && method.equals("GET")) {
            return ticketRoutes.ticketDetail(seg[2]);
        }
        // V5: editable ticket metadata (priority/title/queue stage).
        if (seg.length == 3 && seg[1].equals("tickets") && (method.equals("PATCH") || method.equals("PUT"))) {
            return ticketRoutes.ticketUpdate(seg[2], requestBody);
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
        // Multi-round review history: every captured presubmit round for the ticket.
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("presubmits")
                && method.equals("GET")) {
            return presubmitList(seg[2]);
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
                return sessionRoutes.agentConfigList();
            }
            if (method.equals("POST")) {
                return sessionRoutes.agentConfigCreate(requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("agent-configs")) {
            if (method.equals("GET")) {
                return sessionRoutes.agentConfigDetail(seg[2]);
            }
            if (method.equals("PUT")) {
                return sessionRoutes.agentConfigUpdate(seg[2], requestBody);
            }
            if (method.equals("DELETE")) {
                return sessionRoutes.agentConfigDelete(seg[2]);
            }
        }
        if (seg.length == 4 && seg[1].equals("agent-configs") && seg[3].equals("sessions")
                && method.equals("GET")) {
            return sessionRoutes.agentConfigSessions(seg[2]);
        }

        // S4 session routes (执行文档-后端-web §4.1 会话路由约定).
        if (seg.length == 4 && seg[1].equals("tickets") && seg[3].equals("sessions")) {
            if (method.equals("GET")) {
                return sessionRoutes.ticketSessions(seg[2]);
            }
            if (method.equals("POST")) {
                return sessionRoutes.sessionCreate(seg[2], requestBody);
            }
        }
        if (seg.length == 3 && seg[1].equals("sessions")) {
            if (method.equals("GET")) {
                return sessionRoutes.sessionDetail(seg[2]);
            }
            // Workbench session-list metadata (title/archived) and row removal.
            if (method.equals("PATCH")) {
                return sessionRoutes.sessionPatch(seg[2], requestBody);
            }
            if (method.equals("DELETE")) {
                return sessionRoutes.sessionDelete(seg[2]);
            }
        }
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("messages")) {
            if (method.equals("GET")) {
                return sessionRoutes.sessionHistory(seg[2]);
            }
            if (method.equals("POST")) {
                return sessionRoutes.sessionSend(seg[2], requestBody);
            }
        }
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("abort")
                && method.equals("POST")) {
            return sessionRoutes.sessionAbort(seg[2]);
        }
        // Live model / reasoning-effort switch (会话内实时切换, OpenChamber-style per-session picker).
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("model")
                && method.equals("POST")) {
            return sessionRoutes.sessionModelSet(seg[2], requestBody);
        }
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("models")
                && method.equals("GET")) {
            return sessionRoutes.sessionModels(seg[2]);
        }
        // Permission asks: pending snapshot + per-request replies (opencode sessions).
        if (seg.length == 4 && seg[1].equals("sessions") && seg[3].equals("permissions")
                && method.equals("GET")) {
            return sessionRoutes.permissionList(seg[2]);
        }
        if (seg.length == 5 && seg[1].equals("sessions") && seg[3].equals("permissions")
                && method.equals("POST")) {
            return sessionRoutes.sessionPermissionRespond(seg[2], seg[4], requestBody);
        }
        // 顶栏运行中的智能体数量：GET /api/agents/busy
        if (seg.length == 3 && seg[1].equals("agents") && seg[2].equals("busy")
                && method.equals("GET")) {
            return sessionRoutes.agentsBusy();
        }

        return new Response(404, null); // ApiHandler renders the NOT_FOUND envelope
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

    /**
     * GET /api/tickets/{no}/presubmits — every round captured so far, ascending. {@code
     * changed_count} is derived from the diff blob (number of {@code "diff --git a/"} headers), the
     * same blob the round-diff endpoint reads back. A ticket with no rounds yet is an empty list,
     * not an error — the console renders it as "no review history".
     */
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

    /** Non-overlapping occurrence count ({@link String#split} would need quoting + edge care). */
    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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
        // Tri-state: absent/null human_pass stays null ("nobody decided yet"), so with no engine
        // configured the round degrades fail-closed to REQUIRES_HUMAN instead of collapsing a
        // missing decision into a false → auto-reject (架构规范 I7; handled in ReviewHandler).
        Boolean humanPass = req.get("human_pass") == null ? null
                : Boolean.parseBoolean(req.get("human_pass").toString());
        String note = str(req, "note");
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

    private static Path normalizeWorkspace(String raw) {
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    /**
     * Body: {@code {"name", "workspace_path", "init_git"?, "target_ref"?, "priority"?, "size"?,
     * "tags"?}}. Creates the directory when missing and optionally {@code git init}s it — the
     * console's "选择工作区 → 创建项目" flow. An absent/blank {@code target_ref} is persisted as
     * the gate's primary target ref — the same ref the project's auth repo is seeded with.
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
        String priority = gate.web.ticket.TicketRoutes.parsePriority(req);
        String size = parseProjectSize(req);
        List<String> tags = parseProjectTags(req);
        Instant now = clock.now();
        String id = uniqueProjectId(name);
        // Each project gets its own auth repo so one project's published history can never
        // become another project's clone base (T-107 incident). The repo is seeded with the
        // same baseline + hook semantics as the gate-level auth repo.
        java.nio.file.Path projectAuthRepo = new gate.application.project.ProjectAuthResolver(projects, config)
                .defaultProjectAuthRepo(id);
        // Persist the same effective ref the auth repo is seeded with; a NULL column would
        // resurface as "项目目标分支: null" in injected session context and force every reader
        // to re-derive the fallback.
        String effectiveTargetRef = effectiveTargetRef(targetRef);
        topologyInitializer.initAuthRepo(RepoRef.of(projectAuthRepo), effectiveTargetRef,
                config.approvalsDir());
        Project p = new Project(id, name, workspace.toString(),
                effectiveTargetRef, projectAuthRepo.toString(), priority, size, tags, now, now);
        projects.insert(p);
        return new Response(201, projectJson(p));
    }

    /**
     * Body: {@code {"name"?, "workspace_path"?, "target_ref"?, "priority"?, "size"?, "tags"?}}.
     * Present-but-null priority/size clears the value; an absent key keeps it. {@code target_ref}
     * is never clearable: a present-but-null/blank value resets it to the gate's primary target
     * ref, and legacy NULL rows self-heal the same way on any update. {@code tags} is a full
     * replacement list (null or [] clears all).
     */
    private Response projectUpdate(String id, String requestBody) {
        Project existing = projects.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + id));
        Map<String, Object> req = parseObject(requestBody);
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

    /** Blank or legacy-NULL target refs resolve to the gate's primary (whitelist head). */
    private String effectiveTargetRef(String targetRef) {
        return targetRef == null || targetRef.isBlank() ? config.primaryTargetRef() : targetRef;
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

    /** Real per-project ticket counters — same derivation as {@code ProjectRoutes.projectList}. */
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

    /** Diff payloads above this size get the --ignore-cr-at-eol cross-check in {@link #workingDiff}. */
    private static final int EOL_NOISE_THRESHOLD_CHARS = 100_000;

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

        gate.ports.ProcessRunner.ProcRun tracked = baseCommit == null
                ? git.run(clone, Map.of(), "diff")
                : git.run(clone, Map.of(), "diff", "HEAD");
        String trackedDiff = tracked.ok() ? tracked.stdout() : "";

        // EOL sentinel (T-107 incident): a clone re-checked-out without the pinned
        // core.autocrlf=false holds CRLF bytes over LF blobs, and git then reports every line
        // of every file as changed — tens of thousands of noise lines that freeze the diff
        // console. When the diff is huge but collapses under --ignore-cr-at-eol, serve the
        // normalized diff plus a warning instead. Genuine large diffs (lock files, vendored
        // trees) survive normalization unchanged and pass through as-is.
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
