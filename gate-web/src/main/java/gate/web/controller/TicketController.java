package gate.web.controller;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.domain.audit.AuditEvent;
import gate.ports.store.AgentConfigRepository;
import gate.ports.infra.Clock;
import gate.ports.store.AuditLog;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
import gate.ports.store.TicketStageChangeRepository;
import gate.ports.git.TopologyInitializer;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ticket Management Controller.
 * Owns /api/tickets and /api/projects/{projectId}/tickets routes.
 */
public final class TicketController implements WebController {
    /** Upper bound for one stage-change reason (T-117/V19) — long enough for prose, short enough for prompts. */
    public static final int MAX_REASON_LENGTH = 2000;


    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final AgentConfigRepository agentConfigs;
    private final TopologyInitializer topologyInitializer;
    private final GateConfig config;
    private final Clock clock;
    private final PresubmitRepository presubmits;
    private final TicketStageChangeRepository stageChanges;
    private final AuditLog auditLog;
    private final gate.application.GateService gateService;
    private final gate.ports.infra.TicketLockManager ticketLockManager;

    public TicketController(TicketRepository tickets, ProjectRepository projects,
                            AgentConfigRepository agentConfigs, TopologyInitializer topologyInitializer,
                            GateConfig config, Clock clock,
                            PresubmitRepository presubmits, TicketStageChangeRepository stageChanges,
                            AuditLog auditLog) {
        this(tickets, projects, agentConfigs, topologyInitializer, config, clock,
                presubmits, stageChanges, auditLog, null, null);
    }

    public TicketController(TicketRepository tickets, ProjectRepository projects,
                            AgentConfigRepository agentConfigs, TopologyInitializer topologyInitializer,
                            GateConfig config, Clock clock,
                            PresubmitRepository presubmits, TicketStageChangeRepository stageChanges,
                            AuditLog auditLog, gate.application.GateService gateService,
                            gate.ports.infra.TicketLockManager ticketLockManager) {
        this.tickets = tickets;
        this.projects = projects;
        this.agentConfigs = agentConfigs;
        this.topologyInitializer = topologyInitializer;
        this.config = config;
        this.clock = clock;
        this.presubmits = presubmits;
        this.stageChanges = stageChanges;
        this.auditLog = auditLog;
        this.gateService = gateService;
        this.ticketLockManager = ticketLockManager;
    }

    @Override
    public void register(Javalin app) {
        // Global tickets
        app.get("/api/tickets", this::listGlobalTickets);
        app.post("/api/tickets", this::createGlobalTicket);
        app.get("/api/tickets/{ticketNo}", this::getGlobalTicket);
        app.patch("/api/tickets/{ticketNo}", this::updateGlobalTicket);
        app.put("/api/tickets/{ticketNo}", this::updateGlobalTicket);
        app.get("/api/tickets/{ticketNo}/restarts", this::getGlobalTicketRestarts);
        app.get("/api/tickets/{ticketNo}/stage-changes", this::getGlobalTicketStageChanges);
        app.post("/api/tickets/{ticketNo}/sync-base", this::syncBaseTicket);

        // Project-scoped tickets
        app.get("/api/projects/{projectId}/tickets", this::listProjectTickets);
        app.post("/api/projects/{projectId}/tickets", this::createProjectTicket);
        app.get("/api/projects/{projectId}/tickets/{ticketNo}", this::getProjectTicket);
        app.patch("/api/projects/{projectId}/tickets/{ticketNo}", this::updateProjectTicket);
        app.put("/api/projects/{projectId}/tickets/{ticketNo}", this::updateProjectTicket);
    }

    public void listGlobalTickets(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(renderTicketList(null));
    }

    public void createGlobalTicket(Context ctx) {
        ctx.status(HttpStatus.CREATED);
        ctx.json(createTicket(ctx.body(), null));
    }

    public void getGlobalTicket(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        ctx.status(HttpStatus.OK);
        ctx.json(ticketJson(t, projectNameIndex(), reviveCount(ticketNo)));
    }

    public void updateGlobalTicket(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        ctx.status(HttpStatus.OK);
        ctx.json(updateTicket(ticketNo, ctx.body()));
    }

    public void listProjectTickets(Context ctx) {
        String projectId = ctx.pathParam("projectId");
        requireProject(projectId);
        ctx.status(HttpStatus.OK);
        ctx.json(renderTicketList(projectId));
    }

    public void createProjectTicket(Context ctx) {
        String projectId = ctx.pathParam("projectId");
        requireProject(projectId);
        ctx.status(HttpStatus.CREATED);
        ctx.json(createTicket(ctx.body(), projectId));
    }

    public void getProjectTicket(Context ctx) {
        String projectId = ctx.pathParam("projectId");
        String ticketNo = ctx.pathParam("ticketNo");
        Ticket t = ticketInProject(projectId, ticketNo);
        ctx.status(HttpStatus.OK);
        ctx.json(ticketJson(t, projectNameIndex(), reviveCount(ticketNo)));
    }

    public void updateProjectTicket(Context ctx) {
        String projectId = ctx.pathParam("projectId");
        String ticketNo = ctx.pathParam("ticketNo");
        ticketInProject(projectId, ticketNo);
        ctx.status(HttpStatus.OK);
        ctx.json(updateTicket(ticketNo, ctx.body()));
    }

    public Project requireProject(String projectId) {
        return projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
    }

    private Map<String, Object> renderTicketList(String projectId) {
        Map<String, String> projectNames = projectNameIndex();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Ticket> rows = projectId == null ? tickets.findAll() : tickets.findAllByProject(projectId);
        for (Ticket t : rows) {
            out.add(ticketJson(t, projectNames, reviveCount(t.ticketNo())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tickets", out);
        return body;
    }

    private Map<String, Object> createTicket(String requestBody, String scopedProjectId) {
        Map<String, Object> req = Json.parseObject(requestBody);
        // Same parser the MCP ticket_create tool uses — one rule set for both entry points (T-108).
        // Validation failures (missing/blank/wrong-type/out-of-range fields) carry a structured
        // detail list naming every broken field via the standard error envelope.
        var parsed = gate.application.ticket.TicketRequestParser.parse(
                req, gate.application.ticket.TicketRequestParser.WEB_KEYS);
        String requestedProjectId = parsed.projectId();
        if (scopedProjectId != null && requestedProjectId != null
                && !scopedProjectId.equals(requestedProjectId)) {
            throw new GateException(GateErrorCode.USAGE,
                    "project_id does not match the project ticket board");
        }
        String projectId = scopedProjectId != null ? scopedProjectId : requestedProjectId;
        String agentConfigId = parsed.agentConfigId();
        if (agentConfigId != null && agentConfigs.find(agentConfigId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + agentConfigId);
        }
        // One creation path with the MCP ticket_create tool — validation, auto numbering and the
        // clone materialization cannot drift between the two entry points.
        Ticket t = gateService.createTicket(new gate.application.ticket.CreateTicketCommand(
                parsed.ticketNo(),
                parsed.title(),
                projectId,
                parsed.targetBranch(),
                parsed.stage(),
                parsed.priority(),
                parsed.description(),
                parsed.note(),
                parsed.labels(),
                agentConfigId));
        return ticketJson(t, projectNameIndex());
    }

    private Map<String, Object> updateTicket(String ticketNo, String requestBody) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Map<String, Object> req = Json.parseObject(requestBody);
        if (req.containsKey("target_ref") || req.containsKey("target_branch")) {
            throw new GateException(GateErrorCode.USAGE,
                    "target ref is locked at ticket creation and cannot be changed: " + ticketNo);
        }
        boolean hasTitle = req.containsKey("title");
        boolean hasPriority = req.containsKey("priority");
        boolean hasDescription = req.containsKey("description");
        boolean hasNote = req.containsKey("note");
        boolean hasLabels = req.containsKey("labels");
        boolean hasAgentConfig = req.containsKey("agent_config_id");
        boolean hasStage = req.containsKey("stage");
        boolean hasEditable = hasTitle || hasPriority || hasDescription || hasNote || hasLabels;

        if (!hasStage && !hasEditable && !hasAgentConfig) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide stage, title, priority, description, note, labels or agent_config_id");
        }

        String title = hasTitle ? str(req, "title") : null;
        if (title != null && title.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "title must not be blank");
        }
        String priority = hasPriority ? parsePriority(req) : t.priority();
        String description = hasDescription ? optionalText(req, "description") : t.description();
        String note = hasNote ? optionalText(req, "note") : t.note();
        List<String> labels = hasLabels ? parseTicketLabels(req) : t.labels();

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
        if (hasStage) {
            TicketStage next = parseStage(str(req, "stage"));
            if (t.isSuper()) {
                // V19 快速模式: the project's super ticket is a permanent resident — it never
                // closes, never cancels, never reopens; its stage is not an operator lever.
                throw new GateException(GateErrorCode.USAGE,
                        "quick-mode super ticket " + ticketNo
                                + " never closes and never changes stage");
            }
            // T-117: restarting a terminal (DONE/CANCELLED) ticket is a first-class transition
            // that must carry an operator-supplied reason — no silent revives.
            boolean restart = next == TicketStage.IN_PROGRESS && t.stage().isTerminal();
            // 终态不出门（V19 口径收紧）：终态工单唯一出口是带理由重启转回 IN_PROGRESS，
            // 终态之间的互转（如 已取消 → 强制已完成）一律拒绝，避免"弹窗确认后被打回"的假可用路径。
            if (t.stage().isTerminal() && !restart && next != t.stage()) {
                throw new GateException(GateErrorCode.USAGE,
                        "terminal ticket cannot change stage; restart it with a reason to reopen ("
                                + t.stage() + " -> " + next + ")");
            }
            // V19: user-driven terminalization — force-drag to DONE or cancel from any
            // non-terminal stage; both must carry a reason (same account as the restart reason).
            boolean forceComplete = next == TicketStage.DONE && !t.stage().isTerminal();
            boolean cancel = next == TicketStage.CANCELLED && !t.stage().isTerminal();
            String reason = null;
            if (restart) {
                reason = stageChangeReason(req, "restart_reason");
                requireReason(reason, "restarting a " + t.stage() + " ticket");
            } else if (forceComplete || cancel) {
                reason = stageChangeReason(req, "reason");
                requireReason(reason, forceComplete
                        ? "force-completing a " + t.stage() + " ticket"
                        : "cancelling a " + t.stage() + " ticket");
            } else {
                ensureQueueTransition(t.stage(), next);
            }
            tickets.updateStage(ticketNo, next, clock.now());
            if (restart) {
                recordStageChange(t, next, reason, "restart");
            } else if (forceComplete) {
                recordStageChange(t, next, reason, "force_complete");
            } else if (cancel) {
                recordStageChange(t, next, reason, "cancel");
            }
        }
        if (hasEditable) {
            tickets.updateEditable(ticketNo, title, priority, description, note, labels, clock.now());
        }
        if (hasAgentConfig) {
            tickets.updateAgentConfig(ticketNo, agentConfigId, clock.now());
        }
        return ticketJson(
                tickets.find(ticketNo).orElseThrow(() -> new GateException(
                        GateErrorCode.USAGE, "no such ticket: " + ticketNo)),
                projectNameIndex(), reviveCount(ticketNo));
    }

    private Ticket ticketInProject(String projectId, String ticketNo) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        if (t.projectId() == null || !t.projectId().equals(projectId)) {
            throw new GateException(GateErrorCode.USAGE,
                    "ticket " + ticketNo + " does not belong to project " + projectId);
        }
        return t;
    }

    private String parseAgentConfigId(Map<String, Object> req) {
        String id = str(req, "agent_config_id");
        if (id == null || id.isBlank()) {
            return config.session() == null ? null : config.session().defaultAgentConfig();
        }
        if (agentConfigs.find(id.trim()).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        return id.trim();
    }

    /** Revive history size for the ticket JSON badge (0 when stage-change storage is absent). */
    private long reviveCount(String ticketNo) {
        return stageChanges == null ? 0
                : stageChanges.findByTicket(ticketNo).stream()
                        .filter(TicketStageChangeRepository.StageChangeRow::isRevive).count();
    }

    private long stageChangeCount(String ticketNo) {
        return stageChanges == null ? 0 : stageChanges.count(ticketNo);
    }

    /**
     * 当前编码轮次（看板「第 N 轮」徽标）：轮次与已锁定快照的审查轮对齐（提审分配
     * MAX(review_round)+1，见 {@link #recordStageChange}），当前轮 = 快照最大轮与状态变更行
     * 最大轮（重启在提审前就预占下一轮）取大者；从未提审的工单按第 1 轮计。
     */
    private int currentRound(String ticketNo) {
        int round = 1;
        if (presubmits != null) {
            round = Math.max(round, presubmits.nextRound(ticketNo) - 1);
        }
        if (stageChanges != null) {
            for (TicketStageChangeRepository.StageChangeRow row : stageChanges.findByTicket(ticketNo)) {
                round = Math.max(round, row.round());
            }
        }
        return round;
    }

    /** `reason`, with the revive flow also accepting its legacy `restart_reason` alias. */
    private static String stageChangeReason(Map<String, Object> req, String primaryKey) {
        String reason = optionalText(req, primaryKey);
        if (reason == null && !"reason".equals(primaryKey)) {
            reason = optionalText(req, "reason");
        }
        return reason;
    }

    private static void requireReason(String reason, String verb) {
        if (reason == null) {
            throw new GateException(GateErrorCode.USAGE, verb + " requires a non-blank reason (状态变更理由)");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new GateException(GateErrorCode.USAGE,
                    "reason longer than " + MAX_REASON_LENGTH + " chars");
        }
    }

    /**
     * Records one operator-driven stage change (T-117 重启 / V19 强制已完成、取消): history row +
     * audit event. The {@code round} stored on the row is the presubmit round open at change time
     * ({@code nextRound}); presubmit keeps allocating MAX(review_round)+1, so numbering lines up.
     */
    private void recordStageChange(Ticket before, TicketStage to, String reason, String kind) {
        if (stageChanges == null) {
            throw new GateException(GateErrorCode.USAGE,
                    "stage change history is not supported by this repository configuration");
        }
        int round = presubmits == null ? stageChanges.findByTicket(before.ticketNo()).size() + 1
                : presubmits.nextRound(before.ticketNo());
        stageChanges.insert(new TicketStageChangeRepository.StageChangeRow(
                before.ticketNo(), round, before.stage(), to, reason, clock.now()));
        if (auditLog != null) {
            auditLog.append(AuditEvent.of(clock.now(), "ticket." + kind, before.ticketNo(), round, Map.of(
                    "from_stage", before.stage().name(),
                    "to_stage", to.name(),
                    "reason", reason)));
        }
    }

    /**
     * GET /api/tickets/{ticketNo}/restarts — the revive (terminal → IN_PROGRESS) history, oldest
     * first (T-117). Kept for the legacy badge; the unified view is {@code /stage-changes}.
     */
    public void getGlobalTicketRestarts(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        List<Map<String, Object>> out = new ArrayList<>();
        if (stageChanges != null) {
            for (TicketStageChangeRepository.StageChangeRow r : stageChanges.findByTicket(ticketNo)) {
                if (!r.isRevive()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("round", r.round());
                m.put("from_stage", r.fromStage().name());
                m.put("reason", r.reason());
                m.put("created_at", r.createdAt() == null ? null : r.createdAt().toString());
                out.add(m);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restarts", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** GET /api/tickets/{ticketNo}/stage-changes — every reason-carrying stage change, oldest first (V19). */
    public void getGlobalTicketStageChanges(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        List<Map<String, Object>> out = new ArrayList<>();
        if (stageChanges != null) {
            for (TicketStageChangeRepository.StageChangeRow r : stageChanges.findByTicket(ticketNo)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("round", r.round());
                m.put("from_stage", r.fromStage().name());
                m.put("to_stage", r.effectiveToStage().name());
                m.put("kind", r.isRevive()
                        ? "restart"
                        : r.effectiveToStage() == TicketStage.DONE ? "force_complete" : "cancel");
                m.put("reason", r.reason());
                m.put("created_at", r.createdAt() == null ? null : r.createdAt().toString());
                out.add(m);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stage_changes", out);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /**
     * POST /api/tickets/{ticketNo}/sync-base — fast-forwards the ticket's clone and authoritative
     * branch onto the current base-branch tip (T-118 基座同步). Uncommitted worktree changes are
     * stashed and replayed (body {@code {"allow_dirty":false}} skips a dirty clone instead).
     * Refused while a session is touching the clone (ticket lock).
     */
    public void syncBaseTicket(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        if (gateService == null || ticketLockManager == null) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "base sync is not wired into this web instance");
        }
        String body = ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body();
        Map<String, Object> req = Json.parseObject(body);
        boolean allowDirty = !Boolean.FALSE.equals(req.get("allow_dirty"));
        try (AutoCloseable ignored = ticketLockManager.tryAcquire(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.REJECT_PRECONDITION,
                        "session in progress on this clone; base sync refused while a session is active"))) {
            var r = gateService.syncBase(
                    new gate.application.basesync.SyncBaseCommand(ticketNo, allowDirty, "manual"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ticket_no", ticketNo);
            out.put("status", r.status());
            out.put("behind", r.behind());
            out.put("from_tip", r.fromTip());
            out.put("to_tip", r.toTip());
            out.put("branch_moved", r.branchMoved());
            out.put("conflicts", r.conflicts());
            out.put("stash_kept", r.stashKept());
            if (r.skippedReason() != null) {
                out.put("skipped_reason", r.skippedReason());
            }
            if (r.importKind() != null) {
                out.put("import_kind", r.importKind());
                if (r.importReason() != null) {
                    out.put("import_reason", r.importReason());
                }
            }
            ctx.status(HttpStatus.OK);
            ctx.json(out);
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "base sync failed", e);
        }
    }

    public static String parsePriority(Map<String, Object> req) {
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

    public static String optionalText(Map<String, Object> req, String key) {
        Object raw = req.get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    public static List<String> parseTicketLabels(Map<String, Object> req) {
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
                throw new GateException(GateErrorCode.USAGE, "tag must not be null");
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

    public static TicketStage parseStage(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "stage must not be blank");
        }
        try {
            return TicketStage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GateException(GateErrorCode.USAGE, "no such stage: " + raw);
        }
    }

    public static void ensureQueueTransition(TicketStage from, TicketStage to) {
        Set<TicketStage> reviewGated = Set.of(TicketStage.PRESUBMITTED, TicketStage.IN_REVIEW,
                TicketStage.READY_TO_PUBLISH);
        boolean toInProgress = to == TicketStage.IN_PROGRESS
                && !reviewGated.contains(from);
        // 进行中可退回待处理（看板拖回重新排队）：纯队列内流转，门禁态与终态不受此放行影响。
        boolean toPending = to == TicketStage.PENDING && from == TicketStage.IN_PROGRESS;
        boolean toCancelled = to == TicketStage.CANCELLED
                && !reviewGated.contains(from) && from != TicketStage.CANCELLED;
        if (from == to || toInProgress || toPending || toCancelled) {
            return;
        }
        throw new GateException(GateErrorCode.USAGE,
                "review-gated stage; use presubmit/review/publish endpoints (" + from + " -> " + to + ")");
    }

    private Map<String, String> projectNameIndex() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Project p : projects.findAll()) {
            map.put(p.id(), p.name());
        }
        return map;
    }

    private Map<String, Object> ticketJson(Ticket t, Map<String, String> projectNames) {
        return ticketJson(t, projectNames, 0);
    }

    private Map<String, Object> ticketJson(Ticket t, Map<String, String> projectNames,
                                                 long reviveCount) {
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
        m.put("restart_count", reviveCount);
        m.put("stage_change_count", stageChangeCount(t.ticketNo()));
        m.put("review_round", currentRound(t.ticketNo()));
        m.put("is_super", t.isSuper());
        m.put("created_at", t.createdAt() == null ? null : t.createdAt().toString());
        m.put("updated_at", t.updatedAt() == null ? null : t.updatedAt().toString());
        return m;
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }
}
