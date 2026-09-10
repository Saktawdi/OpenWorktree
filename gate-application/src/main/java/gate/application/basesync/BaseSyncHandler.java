package gate.application.basesync;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.git.BaseSynchronizer;
import gate.ports.git.CloneBaseSyncer;
import gate.ports.store.AuditLog;
import gate.ports.infra.Clock;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
import java.util.Map;
import java.util.Set;

/**
 * Base-sync capability handler (T-118 基座同步).
 *
 * <p>Stage guards encode whose invariant would break: while a review round is open
 * (PRESUBMITTED/IN_REVIEW/READY_TO_PUBLISH) the reviewed diff is relative to the current base —
 * moving that base would silently invalidate the round's TOCTOU anchor, so the base is frozen
 * until the round resolves. Terminal tickets have no live clone semantics; restart them first
 * (T-117), which is exactly the flow that leaves clones needing a sync.
 */
public final class BaseSyncHandler implements BaseSynchronizer {

    private static final Set<TicketStage> REVIEW_GATED = Set.of(
            TicketStage.PRESUBMITTED, TicketStage.IN_REVIEW, TicketStage.READY_TO_PUBLISH);

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final GateConfig config;
    private final gate.application.project.ProjectAuthResolver authResolver;
    private final CloneBaseSyncer syncer;
    private final AuditLog auditLog;
    private final Clock clock;

    public BaseSyncHandler(TicketRepository tickets, ProjectRepository projects, GateConfig config,
                           CloneBaseSyncer syncer, AuditLog auditLog, Clock clock) {
        this.tickets = tickets;
        this.projects = projects;
        this.config = config;
        this.authResolver = new gate.application.project.ProjectAuthResolver(projects, config);
        this.syncer = syncer;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Override
    public CloneBaseSyncer.Report syncBase(String ticketNo, boolean allowDirty) {
        return handle(new SyncBaseCommand(ticketNo, allowDirty, "auto"));
    }

    public CloneBaseSyncer.Report handle(SyncBaseCommand command) {
        Ticket ticket = tickets.find(command.ticketNo()).orElseThrow(
                () -> new GateException(GateErrorCode.USAGE, "no such ticket: " + command.ticketNo()));
        if (ticket.isSuper()) {
            throw new GateException(GateErrorCode.USAGE,
                    "quick-mode super ticket " + ticket.ticketNo() + " operates on the project workspace itself; base sync does not apply");
        }
        if (ticket.stage().isTerminal()) {
            throw new GateException(GateErrorCode.USAGE, "ticket is " + ticket.stage()
                    + "; restart it before syncing the base");
        }
        if (REVIEW_GATED.contains(ticket.stage())) {
            throw new GateException(GateErrorCode.USAGE, "base is frozen while ticket "
                    + ticket.ticketNo() + " is " + ticket.stage()
                    + "; sync after the round resolves");
        }

        var topology = authResolver.forTicket(ticket);
        String baseRef = authResolver.baseRefFor(ticket);

        // T-125: humans commit in the registered workspace, not in the gate-owned mirror. Without
        // importing the workspace's base branch first, the mirror — and with it every clone —
        // never sees that work, and every sync truthfully reports "up_to_date" against a base
        // that is itself stale. Read-only on the workspace and fail-open: a refused import
        // degrades to an audit note and the sync proceeds against the mirror as-is.
        CloneBaseSyncer.ImportResult imported = null;
        java.nio.file.Path workspace = authResolver.workspaceFor(ticket).orElse(null);
        if (workspace != null) {
            imported = syncer.importWorkspaceBase(RepoRef.of(workspace), topology.authRepo(), baseRef);
            if (imported.kind() != CloneBaseSyncer.ImportKind.UP_TO_DATE) {
                auditLog.append(gate.domain.audit.AuditEvent.of(clock.now(),
                        imported.kind() == CloneBaseSyncer.ImportKind.SKIPPED
                                ? "basesync.import_skipped" : "basesync.import",
                        ticket.ticketNo(), null,
                        Map.of("trigger", command.trigger(),
                                "kind", imported.kind().name().toLowerCase(java.util.Locale.ROOT),
                                "tip", imported.tip() == null ? "" : imported.tip(),
                                "reason", imported.skippedReason() == null
                                        ? "" : imported.skippedReason())));
            }
        }

        CloneBaseSyncer.Report report = syncer.sync(
                RepoRef.of(java.nio.file.Path.of(ticket.clonePath())),
                topology.authRepo(), ticket.targetRef(), baseRef,
                command.allowDirty()).withImport(imported);

        String event = "skipped".equals(report.status()) ? "basesync.skipped" : "basesync.ok";
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("trigger", command.trigger());
        fields.put("status", report.status());
        fields.put("behind", String.valueOf(report.behind()));
        fields.put("from", report.fromTip() == null ? "" : report.fromTip());
        fields.put("to", report.toTip() == null ? "" : report.toTip());
        fields.put("auth_moved", String.valueOf(report.branchMoved()));
        fields.put("conflicts", String.join(",", report.conflicts()));
        fields.put("reason", report.skippedReason() == null ? "" : report.skippedReason());
        if (report.importKind() != null) {
            fields.put("import", report.importKind());
            if (report.importReason() != null) {
                fields.put("import_reason", report.importReason());
            }
        }
        auditLog.append(gate.domain.audit.AuditEvent.of(clock.now(), event, ticket.ticketNo(), null, fields));
        return report;
    }
}
