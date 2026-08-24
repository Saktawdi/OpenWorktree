package gate.web.controller;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.store.AgentConfigRepository;
import gate.ports.infra.Clock;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
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

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final AgentConfigRepository agentConfigs;
    private final TopologyInitializer topologyInitializer;
    private final GateConfig config;
    private final Clock clock;

    public TicketController(TicketRepository tickets, ProjectRepository projects,
                            AgentConfigRepository agentConfigs, TopologyInitializer topologyInitializer,
                            GateConfig config, Clock clock) {
        this.tickets = tickets;
        this.projects = projects;
        this.agentConfigs = agentConfigs;
        this.topologyInitializer = topologyInitializer;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public void register(Javalin app) {
        // Global tickets
        app.get("/api/tickets", this::listGlobalTickets);
        app.post("/api/tickets", this::createGlobalTicket);
        app.get("/api/tickets/{ticketNo}", this::getGlobalTicket);
        app.patch("/api/tickets/{ticketNo}", this::updateGlobalTicket);
        app.put("/api/tickets/{ticketNo}", this::updateGlobalTicket);

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
        ctx.json(ticketJson(t, projectNameIndex()));
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
        ctx.json(ticketJson(t, projectNameIndex()));
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
            out.add(ticketJson(t, projectNames));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tickets", out);
        return body;
    }

    private Map<String, Object> createTicket(String requestBody, String scopedProjectId) {
        Map<String, Object> req = Json.parseObject(requestBody);
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
        TicketStage stage = TicketStage.IN_PROGRESS;
        String stageRaw = str(req, "stage");
        if (stageRaw != null) {
            TicketStage parsed = parseStage(stageRaw);
            if (parsed != TicketStage.PENDING && parsed != TicketStage.IN_PROGRESS) {
                throw new GateException(GateErrorCode.USAGE,
                        "new ticket stage must be PENDING or IN_PROGRESS, got " + stageRaw);
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
        var topology = new gate.application.project.ProjectAuthResolver(projects, config).forNewTicket(project);
        String primaryRef = topology.targetRef();
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
        Ticket t = new Ticket(ticketNo, title, targetRef, clone.pathString(),
                null, null, "manual", "human", stage, now, now,
                null, null, agentConfigId, priority, projectId,
                description, note, labels);
        tickets.insert(t);
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
            ensureQueueTransition(t.stage(), next);
            tickets.updateStage(ticketNo, next, clock.now());
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
                projectNameIndex());
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
        boolean toCancelled = to == TicketStage.CANCELLED
                && !reviewGated.contains(from) && from != TicketStage.CANCELLED;
        if (from == to || toInProgress || toCancelled) {
            return;
        }
        throw new GateException(GateErrorCode.USAGE,
                "review-gated stage; use presubmit/review/publish endpoints (" + from + " -> " + to + ")");
    }

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

    private Map<String, String> projectNameIndex() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Project p : projects.findAll()) {
            map.put(p.id(), p.name());
        }
        return map;
    }

    private Map<String, Object> ticketJson(Ticket t, Map<String, String> projectNames) {
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

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }
}
