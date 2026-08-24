package gate.application;
import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.application.status.ReconcileCommand;
import gate.application.presubmit.PresubmitCommand;
import gate.application.presubmit.PresubmitResult;
import gate.application.status.StatusResult;
import gate.application.status.ReconcileResult;
import gate.application.publish.PublishResult;
import gate.application.status.StatusQuery;
import gate.application.publish.PublishCommand;


import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.policy.GatePolicy;
import gate.domain.ticket.Ticket;
import gate.ports.store.ApprovalStore;
import gate.ports.store.AuditLog;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.git.CommitPublisher;
import gate.ports.infra.DbTransactionRunner;
import gate.ports.infra.LockManager;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.PublishIntentRepository;
import gate.ports.git.RefObserver;
import gate.ports.engine.ReviewEngineFactory;
import gate.ports.store.ReviewResultRepository;
import gate.ports.git.SnapshotCapture;
import gate.ports.store.TicketRepository;
import gate.ports.git.WorkspaceSyncer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single application service facade wiring the pipeline of 架构落地执行文档 §3.
 *
 * <p>Two structural rules dominate this system:
 *
 * <p><b>Order of durability on the publish path (§7.4).</b> The sequence is: write the
 * {@code publish_intent} row (durable, {@code synchronous=FULL}) → build the deterministic commit →
 * issue the approval → push → observe the ref → record the outcome. The intent is written before any
 * irreversible step so every crash point C0–C5 has a persistent anchor and recovery is unambiguous.
 *
 * <p><b>No SQLite transaction ever spans a git call (I3).</b> DB writes happen inside
 * {@code tx.inTransaction(...)} blocks that contain no ProcessRunner-backed work; git runs
 * strictly between those blocks.
 */
public final class GateServiceImpl implements GateService {

    private final GateConfig config;
    private final RefObserver refObserver;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final PublishIntentRepository intents;
    private final gate.application.presubmit.PresubmitHandler presubmitHandler;
    private final gate.application.review.ReviewHandler reviewHandler;
    private final gate.application.publish.PublishHandler publishHandler;
    private final gate.application.project.ProjectAuthResolver authResolver;

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, reviewEngineFactory, gatePolicy,
                tickets, presubmits, reviewResults, intents, blobStore, auditLog, lockManager, tx, clock,
                gate.ports.engine.PublishProbe.NOOP, null, null, null);
    }

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock,
                           gate.ports.engine.PublishProbe publishProbe) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, reviewEngineFactory, gatePolicy,
                tickets, presubmits, reviewResults, intents, blobStore, auditLog, lockManager, tx, clock, publishProbe, null, null, null);
    }

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock,
                           gate.ports.engine.PublishProbe publishProbe, gate.ports.git.AuthoritativeGitService authoritativeGitService) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, reviewEngineFactory, gatePolicy,
                tickets, presubmits, reviewResults, intents, blobStore, auditLog, lockManager, tx, clock, publishProbe,
                authoritativeGitService, null, null);
    }

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock,
                           gate.ports.engine.PublishProbe publishProbe, gate.ports.git.AuthoritativeGitService authoritativeGitService,
                           gate.ports.store.ProjectRepository projects) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, reviewEngineFactory, gatePolicy,
                tickets, presubmits, reviewResults, intents, blobStore, auditLog, lockManager, tx, clock,
                publishProbe, authoritativeGitService, projects, null);
    }

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock,
                           gate.ports.engine.PublishProbe publishProbe, gate.ports.git.AuthoritativeGitService authoritativeGitService,
                           gate.ports.store.ProjectRepository projects, WorkspaceSyncer workspaceSyncer) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, reviewEngineFactory, gatePolicy,
                tickets, presubmits, reviewResults, intents, blobStore, auditLog, lockManager, tx, clock,
                publishProbe, authoritativeGitService, projects, workspaceSyncer, null);
    }

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock,
                           gate.ports.engine.PublishProbe publishProbe, gate.ports.git.AuthoritativeGitService authoritativeGitService,
                           gate.ports.store.ProjectRepository projects, WorkspaceSyncer workspaceSyncer,
                           gate.ports.git.CommitIdentityProvider commitIdentityProvider) {
        this.config = config;
        this.refObserver = refObserver;
        this.tickets = tickets;
        this.presubmits = presubmits;
        this.intents = intents;
        this.authResolver = new gate.application.project.ProjectAuthResolver(projects, config);
        this.presubmitHandler = new gate.application.presubmit.PresubmitHandler(
                config, snapshotCapture, tickets, presubmits, blobStore, auditLog, tx, clock, this.authResolver);
        this.reviewHandler = new gate.application.review.ReviewHandler(
                config, snapshotCapture, commitPublisher, approvalStore, reviewEngineFactory,
                gatePolicy, tickets, presubmits, reviewResults, blobStore, auditLog, tx, clock, this.authResolver);
        this.publishHandler = new gate.application.publish.PublishHandler(
                config, snapshotCapture, commitPublisher, refObserver, approvalStore,
                gatePolicy, tickets, presubmits, reviewResults, intents, blobStore,
                auditLog, lockManager, tx, clock, publishProbe, authoritativeGitService, this.authResolver,
                workspaceSyncer, commitIdentityProvider);
    }

    @Override
    public PresubmitResult presubmit(PresubmitCommand command) {
        return presubmitHandler.handle(command);
    }

    @Override
    public ReviewResult review(ReviewCommand command) {
        return reviewHandler.handle(command);
    }

    @Override
    public PublishResult publish(PublishCommand command) {
        return publishHandler.handle(command);
    }

    @Override
    public ReconcileResult reconcile(ReconcileCommand command) {
        return publishHandler.reconcile(command);
    }

    @Override
    public StatusResult status(StatusQuery query) {
        RepoRef auth = RepoRef.of(config.authRepo());
        String targetRef = config.primaryTargetRef();
        boolean authPresent = Files.exists(config.authRepo());
        String authTip = authPresent ? refObserver.tip(auth, targetRef).map(gate.domain.git.ObjectId::hex).orElse("") : null;
        long count = authPresent ? refObserver.countCommits(auth, targetRef) : 0L;

        List<Ticket> ticketList = query.ticketNo() == null
                ? tickets.findAll()
                : tickets.find(query.ticketNo()).map(List::of).orElse(List.of());

        List<StatusResult.TicketStatus> statuses = new ArrayList<>();
        for (Ticket t : ticketList) {
            var latest = presubmits.findLatest(t.ticketNo());
            Integer round = latest.map(PresubmitRepository.PresubmitRow::reviewRound).orElse(null);
            String tree = latest.map(r -> r.treeHash().hex()).orElse(null);
            String intentStatus = null;
            String commitSha = null;
            boolean publishedInAuth = false;
            if (latest.isPresent()) {
                var intent = intents.find(t.ticketNo(), latest.get().reviewRound(), latest.get().treeHash());
                if (intent.isPresent()) {
                    intentStatus = intent.get().status().name();
                    commitSha = intent.get().commitSha() == null ? null : intent.get().commitSha().hex();
                    RepoRef ticketAuth = t.projectId() == null ? auth : authResolver.forTicket(t).authRepo();
                    boolean ticketAuthPresent = Files.exists(ticketAuth.path());
                    publishedInAuth = ticketAuthPresent && intent.get().commitSha() != null
                            && refObserver.published(ticketAuth, intent.get().targetRef(), intent.get().commitSha());
                }
            }
            statuses.add(new StatusResult.TicketStatus(t.ticketNo(), t.stage().name(), round, tree,
                    intentStatus, commitSha, publishedInAuth));
        }
        return new StatusResult(targetRef, authTip, count, statuses);
    }
}
