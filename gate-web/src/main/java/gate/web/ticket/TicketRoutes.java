package gate.web.ticket;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.AgentConfigRepository;
import gate.ports.Clock;
import gate.ports.ProjectRepository;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import gate.web.ApiRoutes;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ticket capability handler (EX-001 Phase 1).
 * Owns /api/tickets and /api/projects/{id}/tickets routes.
 * Extracted from ApiRoutes to satisfy GOV-CPLX-001 and capability-registry.
 */
public final class TicketRoutes {
    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final AgentConfigRepository agentConfigs;
    private final TopologyInitializer topologyInitializer;
    private final GateConfig config;
    private final Clock clock;

    public TicketRoutes(TicketRepository tickets, ProjectRepository projects,
                        AgentConfigRepository agentConfigs, TopologyInitializer topologyInitializer,
                        GateConfig config, Clock clock) {
        this.tickets = tickets;
        this.projects = projects;
        this.agentConfigs = agentConfigs;
        this.topologyInitializer = topologyInitializer;
        this.config = config;
        this.clock = clock;
    }

    public ApiRoutes.Response ticketList() {
        return ticketList(null);
    }

    public ApiRoutes.Response ticketList(String projectId) {
        Map<String, String> projectNames = projectNameIndex();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Ticket> rows = projectId == null ? tickets.findAll() : tickets.findAllByProject(projectId);
        for (Ticket t : rows) {
            out.add(ticketJson(t, projectNames));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tickets", out);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response ticketDetail(String ticketNo) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        return new ApiRoutes.Response(200, ticketJson(t, projectNameIndex()));
    }

    public ApiRoutes.Response ticketDetail(String projectId, String ticketNo) {
        Ticket t = ticketInProject(projectId, ticketNo);
        return new ApiRoutes.Response(200, ticketJson(t, projectNameIndex()));
    }

    public ApiRoutes.Response ticketCreate(String requestBody) {
        return ticketCreate(requestBody, null);
    }

    public ApiRoutes.Response ticketCreate(String requestBody, String scopedProjectId) {
        Map<String, Object> req = parseObject(requestBody);
        // ticket_no is optional: omitted/blank lets the server mint the next sequential number.
        String requestedNo = str(req, "ticket_no");
        if (requestedNo != null && requestedNo.isBlank()) {
            requestedNo = null;
        }
        String title = str(req, "title");
        if (title == null) {
            title = "";
        }
        String ticketNo = requestedNo != null ? requestedNo : generateTicketNo();
        if (tickets.find(ticketNo).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "ticket already exists: " + ticketNo);
        }
        // Creation stage: only the two queue-entry stages; later stages are gate-driven.
        TicketStage stage = TicketStage.IN_PROGRESS;
        String stageRaw = str(req, "stage");
        if (stageRaw != null) {
            TicketStage parsed = parseStage(stageRaw);
            if (parsed != TicketStage.PENDING && parsed != TicketStage.IN_PROGRESS) {
                throw new GateException(GateErrorCode.USAGE,
                        "stage on create must be PENDING or IN_PROGRESS: " + parsed);
            }
            stage = parsed;
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
        Project project = null;
        if (projectId != null) {
            final String pid = projectId;
            project = projects.find(pid).orElseThrow(() -> new GateException(
                    GateErrorCode.USAGE, "no such project: " + pid));
        }
        String agentConfigId = str(req, "agent_config_id");
        if (agentConfigId != null && agentConfigId.isBlank()) {
            agentConfigId = null;
        }
        if (agentConfigId != null && agentConfigs.find(agentConfigId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + agentConfigId);
        }

        // Project tickets clone from the project's own auth repo; only unaffiliated tickets use
        // the gate-level topology (ProjectAuthResolver — cross-project clones caused T-107).
        gate.application.project.ProjectAuthResolver.AuthTarget topology =
                new gate.application.project.ProjectAuthResolver(projects, config).forNewTicket(project);
        String primaryRef = topology.targetRef();
        // 工单级目标分支（默认 refs/heads/<工单号>）：克隆、预提审、发布与工作区同步全部锚定它，
        // 天然形成"一工单一分支"的协作形态。创建后锁定——分支换基会破坏 tree 锚定与审计链。
        String targetRef = resolveTicketTargetRef(req, ticketNo);
        RepoRef auth = topology.authRepo();
        if (!java.nio.file.Files.exists(auth.path())) {
            throw new GateException(GateErrorCode.USAGE,
                    "auth repo for this ticket does not exist: " + auth.pathString()
                            + " (init it before creating tickets)");
        }
        if (!targetRef.equals(primaryRef)) {
            topologyInitializer.ensureBranch(auth, targetRef, primaryRef);
        }
        Path cloneDir = config.clonesRoot().resolve(ticketNo);
        RepoRef clone = topologyInitializer.createClone(auth, targetRef, cloneDir);
        Instant now = clock.now();
        tickets.insert(new Ticket(ticketNo, title, targetRef, clone.pathString(),
                null, null, "manual", "human", stage, now, now,
                null, null, agentConfigId, priority, projectId, description, note, labels));
        Ticket created = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.GATE_ERROR_IO, "ticket was created but could not be reloaded: " + ticketNo));
        return new ApiRoutes.Response(201, ticketJson(created, projectNameIndex()));
    }

    /**
     * Server-side ticket numbering: the next free {@code T-<n>}. The scan takes the highest number
     * among existing {@code T-(\d+)} tickets plus one, with a floor of 101 — a fresh gate mints
     * {@code T-101}. The uniqueness loop is a belt-and-braces guard against explicit numbers the
     * scan could not have seen (e.g. a concurrent insert).
     */
    private String generateTicketNo() {
        java.util.regex.Pattern numbered = java.util.regex.Pattern.compile("T-(\\d+)");
        int next = 101;
        for (Ticket t : tickets.findAll()) {
            java.util.regex.Matcher m = numbered.matcher(t.ticketNo());
            if (m.matches()) {
                next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
            }
        }
        while (tickets.find("T-" + next).isPresent()) {
            next++;
        }
        return "T-" + next;
    }

    public Project requireProject(String projectId) {
        return projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
    }

    public Ticket ticketInProject(String projectId, String ticketNo) {
        requireProject(projectId);
        Ticket ticket = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        if (!projectId.equals(ticket.projectId())) {
            throw new GateException(GateErrorCode.USAGE,
                    "ticket " + ticketNo + " does not belong to project " + projectId);
        }
        return ticket;
    }

    public ApiRoutes.Response ticketUpdate(String ticketNo, String requestBody) {
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        return ticketUpdate(t, ticketNo, requestBody);
    }

    public ApiRoutes.Response ticketUpdate(String projectId, String ticketNo, String requestBody) {
        Ticket t = ticketInProject(projectId, ticketNo);
        return ticketUpdate(t, ticketNo, requestBody);
    }

    private ApiRoutes.Response ticketUpdate(Ticket t, String ticketNo, String requestBody) {
        Map<String, Object> req = parseObject(requestBody);
        if (req.containsKey("target_ref") || req.containsKey("target_branch")) {
            throw new GateException(GateErrorCode.USAGE,
                    "target ref is locked at ticket creation and cannot be changed: " + ticketNo);
        }
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
        return new ApiRoutes.Response(200, ticketJson(
                tickets.find(ticketNo).orElseThrow(() -> new GateException(
                        GateErrorCode.USAGE, "no such ticket: " + ticketNo)),
                projectNameIndex()));
    }

    /**
     * 工单目标分支解析：接受短名（{@code fix-docs}）或完整 ref（{@code refs/heads/fix-docs}），
     * 缺省为工单号本身。仅允许单段安全字符集——分支名会进入注入提示词、ref 与克隆参数，
     * 拒绝斜杠/空白/元字符，杜绝 ref 伪造与提示词注入。
     */
    private static String resolveTicketTargetRef(Map<String, Object> req, String ticketNo) {
        Object raw = req.get("target_branch");
        if (raw == null) {
            raw = req.get("target_ref");
        }
        String name;
        if (raw == null || String.valueOf(raw).isBlank()) {
            name = ticketNo;
        } else {
            name = String.valueOf(raw).trim();
            if (name.startsWith("refs/heads/")) {
                name = name.substring("refs/heads/".length());
            }
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".lock")
                || !name.matches("[A-Za-z0-9._-]+") || name.length() > 80) {
            throw new GateException(GateErrorCode.USAGE,
                    "target_branch must match [A-Za-z0-9._-]+ (single segment, no slash): " + raw);
        }
        return "refs/heads/" + name;
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
        boolean toCancelled = to == TicketStage.CANCELLED
                && !reviewGated.contains(from) && from != TicketStage.CANCELLED;
        if (from == to || toInProgress || toCancelled) {
            return;
        }
        throw new GateException(GateErrorCode.USAGE,
                "review-gated stage; use presubmit/review/publish endpoints (" + from + " -> " + to + ")");
    }

    public Map<String, String> projectNameIndex() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Project p : projects.findAll()) {
            names.put(p.id(), p.name());
        }
        return names;
    }

    public static Map<String, Object> ticketJson(Ticket t, Map<String, String> projectNames) {
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
}
