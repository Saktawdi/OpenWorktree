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
        if (ticket.stage().isTerminal()) {
            throw new GateException(GateErrorCode.USAGE, "ticket is " + ticket.stage()
                    + "; restart it before syncing the base (T-117)");
        }
        if (REVIEW_GATED.contains(ticket.stage())) {
            throw new GateException(GateErrorCode.USAGE, "base is frozen while ticket "
                    + ticket.ticketNo() + " is " + ticket.stage()
                    + "; sync after the round resolves");
        }

        var topology = authResolver.forTicket(ticket);

        CloneBaseSyncer.Report report = syncer.sync(
                RepoRef.of(java.nio.file.Path.of(ticket.clonePath())),
                topology.authRepo(), ticket.targetRef(), authResolver.baseRefFor(ticket),
                command.allowDirty());

        String event = "skipped".equals(report.status()) ? "basesync.skipped" : "basesync.ok";
        auditLog.append(gate.domain.audit.AuditEvent.of(clock.now(), event, ticket.ticketNo(), null,
                Map.of("trigger", command.trigger(),
                        "status", report.status(),
                        "behind", String.valueOf(report.behind()),
                        "from", report.fromTip() == null ? "" : report.fromTip(),
                        "to", report.toTip() == null ? "" : report.toTip(),
                        "auth_moved", String.valueOf(report.branchMoved()),
                        "conflicts", String.join(",", report.conflicts()),
                        "reason", report.skippedReason() == null ? "" : report.skippedReason())));
        return report;
    }
}
