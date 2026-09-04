package gate.application.presubmit;

import gate.application.presubmit.PresubmitCommand;
import gate.application.presubmit.PresubmitResult;
import gate.domain.audit.AuditEvent;
import gate.domain.blob.BlobRef;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.snapshot.CaptureIntegrityReport;
import gate.domain.snapshot.Snapshot;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.store.AuditLog;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.infra.DbTransactionRunner;
import gate.ports.store.PresubmitRepository;
import gate.ports.git.SnapshotCapture;
import gate.ports.store.TicketRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Presubmit capability handler (EX-002).
 * Owns presubmit use case extracted from GateServiceImpl (663 lines).
 * Implements TOCTOU capture invariant without holding DB transactions across Git (I3).
 * L2 behavior-preserving extraction.
 */
public final class PresubmitHandler {
    private final GateConfig config;
    private final gate.application.project.ProjectAuthResolver authResolver;
    private final SnapshotCapture snapshotCapture;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final BlobStore blobStore;
    private final AuditLog auditLog;
    private final DbTransactionRunner tx;
    private final Clock clock;

    public PresubmitHandler(GateConfig config, SnapshotCapture snapshotCapture, TicketRepository tickets,
                            PresubmitRepository presubmits, BlobStore blobStore, AuditLog auditLog,
                            DbTransactionRunner tx, Clock clock) {
        this(config, snapshotCapture, tickets, presubmits, blobStore, auditLog, tx, clock, null);
    }

    public PresubmitHandler(GateConfig config, SnapshotCapture snapshotCapture, TicketRepository tickets,
                            PresubmitRepository presubmits, BlobStore blobStore, AuditLog auditLog,
                            DbTransactionRunner tx, Clock clock,
                            gate.application.project.ProjectAuthResolver authResolver) {
        this.config = config;
        this.authResolver = authResolver;
        this.snapshotCapture = snapshotCapture;
        this.tickets = tickets;
        this.presubmits = presubmits;
        this.blobStore = blobStore;
        this.auditLog = auditLog;
        this.tx = tx;
        this.clock = clock;
    }

    public PresubmitResult handle(PresubmitCommand command) {
        Ticket ticket = tickets.find(command.ticketNo()).orElseThrow(
                () -> new GateException(GateErrorCode.USAGE, "no such ticket: " + command.ticketNo()));
        if (ticket.isSuper()) {
            throw new GateException(GateErrorCode.USAGE,
                    "quick-mode super ticket " + ticket.ticketNo() + " works directly on the project workspace and never enters the gate pipeline (presubmit)");
        }
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(ticket.clonePath()));
        RepoRef auth = authFor(ticket);
        String targetRef = ticket.targetRef();

        // Capture is pure git + filesystem work; it holds no DB transaction (I3).
        Snapshot snapshot = snapshotCapture.capture(clone, auth, targetRef);

        if (snapshot.integrity().hasBlockers()) {
            auditLog.append(AuditEvent.of(clock.now(), "presubmit.blocked", ticket.ticketNo(), null, Map.of(
                    "reason", "capture_integrity",
                    "blockers", integrityText(snapshot.integrity()))));
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "capture integrity blockers (no review round consumed): " + integrityText(snapshot.integrity()));
        }

        // Empty diff: refuse WITHOUT allocating a round.
        if (snapshot.isEmptyDiff()) {
            auditLog.append(AuditEvent.of(clock.now(), "presubmit.emptyDiff", ticket.ticketNo(), null, Map.of(
                    "tree", snapshot.treeHash().hex(), "base_tree", snapshot.baseTree().hex())));
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "empty diff: tree == base tree; no review round consumed");
        }

        byte[] diffBytes = snapshot.diff().getBytes(StandardCharsets.UTF_8);
        int round = presubmits.nextRound(ticket.ticketNo());
        BlobRef diffBlob = blobStore.put(diffBytes,
                "diff/" + ticket.ticketNo() + "/" + round + "/diff.patch");

        var row = tx.inTransaction(() -> {
            var inserted = presubmits.insert(ticket.ticketNo(), round, snapshot, diffBlob, clock.now());
            tickets.updateStage(ticket.ticketNo(), TicketStage.PRESUBMITTED, clock.now());
            return inserted;
        });

        auditLog.append(AuditEvent.of(clock.now(), "presubmit.ok", ticket.ticketNo(), round, Map.of(
                "tree", snapshot.treeHash().hex(),
                "base", snapshot.baseCommit().hex(),
                "diff_bytes", String.valueOf(diffBlob.bytes()),
                "diff_sha256", diffBlob.sha256(),
                "changed_paths", String.valueOf(snapshot.changedPaths().size()))));

        return new PresubmitResult(ticket.ticketNo(), row.reviewRound(), snapshot.treeHash().hex(),
                snapshot.baseCommit().hex(), targetRef, diffBlob.bytes(), snapshot.changedPaths(),
                snapshot.integrity());
    }

    /** The ticket's project auth repo when bound, else the gate-level auth repo. */
    private RepoRef authFor(Ticket ticket) {
        return authResolver != null
                ? authResolver.forTicket(ticket).authRepo()
                : RepoRef.of(config.authRepo());
    }

    private static String integrityText(CaptureIntegrityReport report) {
        return report.blockers().stream()
                .map(v -> v.rule() + ":" + v.detail())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }
}
