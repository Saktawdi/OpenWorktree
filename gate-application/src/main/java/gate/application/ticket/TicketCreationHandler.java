package gate.application.ticket;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.git.TopologyInitializer;
import gate.ports.infra.Clock;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
import gate.application.project.ProjectAuthResolver;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Creates a ticket: validates the request against the current state, cuts the ticket branch from
 * the topology's primary ref (server-side, same bootstrap nature as the seed) and materializes the
 * independent clone.
 *
 * <p>One creation path serves both the web console and the agent-facing MCP {@code ticket_create}
 * tool, so the validation rules and the auto numbering (next {@code T-nnn}, base 101) cannot drift
 * between the two entry points. Field-level rules (required title, priority/stage/labels enums,
 * branch format) live in {@link TicketRequestParser}; this handler keeps only what needs the
 * <em>state</em>: duplicate ticket_no, project existence, topology wiring, auth repo presence and
 * clone IO (T-108 — validation failures and domain failures stay distinguishable).
 *
 * <p>Project resolution follows {@link ProjectAuthResolver#forNewTicket}: a bound project clones
 * from its own auth repo (the T-107 fix), everything else falls back to the gate-level topology.
 * {@code projects} may be null in legacy wirings, which then only supports unaffiliated tickets.
 */
public final class TicketCreationHandler {

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final GateConfig config;
    private final TopologyInitializer topologyInitializer;
    private final Clock clock;
    private final gate.ports.git.CloneBaseSyncer cloneBaseSyncer;
    private final gate.ports.store.AuditLog auditLog;

    public TicketCreationHandler(TicketRepository tickets, ProjectRepository projects, GateConfig config,
                                 TopologyInitializer topologyInitializer, Clock clock) {
        this(tickets, projects, config, topologyInitializer, clock, null, null);
    }

    public TicketCreationHandler(TicketRepository tickets, ProjectRepository projects, GateConfig config,
                                 TopologyInitializer topologyInitializer, Clock clock,
                                 gate.ports.git.CloneBaseSyncer cloneBaseSyncer, gate.ports.store.AuditLog auditLog) {
        this.tickets = tickets;
        this.projects = projects;
        this.config = config;
        this.topologyInitializer = topologyInitializer;
        this.clock = clock;
        this.cloneBaseSyncer = cloneBaseSyncer;
        this.auditLog = auditLog;
    }

    public Ticket handle(CreateTicketCommand command) {
        if (topologyInitializer == null) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "ticket creation is not wired into this GateService instance");
        }
        CreateTicketCommand cmd = TicketRequestParser.normalize(command);
        String ticketNo = cmd.ticketNo() != null ? cmd.ticketNo() : generateTicketNo();
        if (tickets.find(ticketNo).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "ticket already exists: " + ticketNo);
        }
        String projectId = cmd.projectId();
        Project project = null;
        if (projectId != null) {
            if (projects == null) {
                throw new GateException(GateErrorCode.USAGE,
                        "projects are not wired into this GateService instance; "
                                + "tickets cannot be bound to a project here");
            }
            final String pid = projectId;
            project = projects.find(pid).orElseThrow(() -> new GateException(
                    GateErrorCode.USAGE, "no such project: " + pid));
        }

        // Project tickets clone from the project's own auth repo; only unaffiliated tickets use
        // the gate-level topology (ProjectAuthResolver — cross-project clones caused T-107).
        var topology = new ProjectAuthResolver(projects, config).forNewTicket(project);
        String primaryRef = topology.targetRef();
        String targetRef = "refs/heads/" + (cmd.targetBranch() != null ? cmd.targetBranch() : ticketNo);
        RepoRef auth = topology.authRepo();
        if (!Files.exists(auth.path())) {
            throw new GateException(GateErrorCode.USAGE,
                    "auth repo for this ticket does not exist: " + auth.pathString()
                            + " (init it before creating tickets)");
        }
        importWorkspaceBase(project, auth, primaryRef);
        if (!targetRef.equals(primaryRef)) {
            topologyInitializer.ensureBranch(auth, targetRef, primaryRef);
        }
        var clone = topologyInitializer.createClone(auth, targetRef,
                config.clonesRoot().resolve(ticketNo));

        TicketStage stage = cmd.stage() == null ? TicketStage.IN_PROGRESS
                : TicketStage.valueOf(cmd.stage());
        Ticket t = new Ticket(ticketNo, cmd.title(), targetRef, clone.pathString(),
                null, null, "manual", "human", stage, clock.now(), clock.now(),
                null, null, cmd.agentConfigId(), cmd.priority(), projectId,
                cmd.description(), cmd.note(), cmd.labels());
        tickets.insert(t);
        return t;
    }

    /** Next free {@code T-nnn}: one past the highest existing number, never below 101. */
    private String generateTicketNo() {
        return nextTicketNo(tickets);
    }

    /**
     * 建票克隆前把注册工作区基分支的最新 tip 导入权威镜像（T-125 同款补环）。
     *
     * <p>不导入的话克隆基座停在注册/上次同步时的旧 tip：源仓库此后产生的新提交只有点
     * 「同步基座」才进得来，新工单一建出来就落后 N 个提交。导入对工作区只读且 fail-open，
     * 任何失败降级为审计备注，绝不阻断建票。
     */
    private void importWorkspaceBase(Project project, RepoRef auth, String primaryRef) {
        if (cloneBaseSyncer == null || project == null) {
            return;
        }
        try {
            var imported = cloneBaseSyncer.importWorkspaceBase(
                    RepoRef.of(java.nio.file.Path.of(project.workspacePath())), auth, primaryRef);
            if (imported.kind() == gate.ports.git.CloneBaseSyncer.ImportKind.UP_TO_DATE || auditLog == null) {
                return;
            }
            auditLog.append(gate.domain.audit.AuditEvent.of(clock.now(),
                    imported.kind() == gate.ports.git.CloneBaseSyncer.ImportKind.SKIPPED
                            ? "basesync.import_skipped" : "basesync.import",
                    null, null,
                    Map.of("trigger", "ticket_create",
                            "project", project.id(),
                            "base_ref", primaryRef,
                            "kind", imported.kind().name().toLowerCase(Locale.ROOT),
                            "tip", imported.tip() == null ? "" : imported.tip(),
                            "reason", imported.skippedReason() == null ? "" : imported.skippedReason())));
        } catch (Exception e) {
            // fail-open：基线导入只是让克隆起点更新的优化，绝不阻断建票
        }
    }

    /** Shared numbering pool for regular tickets and the quick-mode super ticket (V19). */
    public static String nextTicketNo(TicketRepository tickets) {
        Pattern numbered = Pattern.compile("T-(\\d+)");
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
}
