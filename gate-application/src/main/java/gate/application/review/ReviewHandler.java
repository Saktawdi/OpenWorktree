package gate.application.review;

import gate.application.metrics.EvidenceCodec;
import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.domain.audit.AuditEvent;
import gate.domain.blob.BlobRef;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.Decision;
import gate.domain.policy.GatePolicy;
import gate.domain.publish.PublishIntent;
import gate.domain.publish.PublishStatus;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.ReviewEvidence;
import gate.domain.snapshot.Snapshot;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.store.ApprovalStore;
import gate.ports.store.AuditLog;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.git.CommitPublisher;
import gate.ports.session.CostHint;
import gate.ports.infra.DbTransactionRunner;
import gate.ports.store.PresubmitRepository;
import gate.ports.engine.ReviewEngine;
import gate.ports.engine.ReviewEngineFactory;
import gate.ports.store.ReviewResultRepository;
import gate.ports.git.SnapshotCapture;
import gate.ports.store.TicketRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Review capability handler (EX-002).
 * Owns GatePolicy verdict, engine orchestration, review_result core fields.
 * Extracted from GateServiceImpl to satisfy GOV-CPLX-001.
 */
public final class ReviewHandler {
    private final GateConfig config;
    private final gate.application.project.ProjectAuthResolver authResolver;
    private final SnapshotCapture snapshotCapture;
    private final CommitPublisher commitPublisher;
    private final ApprovalStore approvalStore;
    private final ReviewEngineFactory reviewEngineFactory;
    private final GatePolicy gatePolicy;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final ReviewResultRepository reviewResults;
    private final BlobStore blobStore;
    private final AuditLog auditLog;
    private final DbTransactionRunner tx;
    private final Clock clock;

    public ReviewHandler(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                         ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                         GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                         ReviewResultRepository reviewResults, BlobStore blobStore, AuditLog auditLog,
                         DbTransactionRunner tx, Clock clock) {
        this(config, snapshotCapture, commitPublisher, approvalStore, reviewEngineFactory,
                gatePolicy, tickets, presubmits, reviewResults, blobStore, auditLog, tx, clock, null);
    }

    public ReviewHandler(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                         ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                         GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                         ReviewResultRepository reviewResults, BlobStore blobStore, AuditLog auditLog,
                         DbTransactionRunner tx, Clock clock,
                         gate.application.project.ProjectAuthResolver authResolver) {
        this.config = config;
        this.authResolver = authResolver;
        this.snapshotCapture = snapshotCapture;
        this.commitPublisher = commitPublisher;
        this.approvalStore = approvalStore;
        this.reviewEngineFactory = reviewEngineFactory;
        this.gatePolicy = gatePolicy;
        this.tickets = tickets;
        this.presubmits = presubmits;
        this.reviewResults = reviewResults;
        this.blobStore = blobStore;
        this.auditLog = auditLog;
        this.tx = tx;
        this.clock = clock;
    }

    public ReviewResult handle(ReviewCommand command) {
        Ticket ticket = tickets.find(command.ticketNo()).orElseThrow(
                () -> new GateException(GateErrorCode.USAGE, "no such ticket: " + command.ticketNo()));
        if (ticket.isSuper()) {
            throw new GateException(GateErrorCode.USAGE,
                    "quick-mode super ticket " + ticket.ticketNo() + " works directly on the project workspace and never enters the gate pipeline (review)");
        }
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(ticket.clonePath()));

        var presubmit = command.round() == null
                ? presubmits.findLatest(ticket.ticketNo())
                : presubmits.find(ticket.ticketNo(), command.round());
        var row = presubmit.orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                "no presubmit round for " + ticket.ticketNo()
                        + (command.round() == null ? "" : "/" + command.round())));

        byte[] diff = blobStore.get(new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256()));
        Snapshot snapshot = snapshotCapture.rebuild(clone, row.targetRef(), row.treeHash(), row.baseCommit(),
                new String(diff, StandardCharsets.UTF_8));

        // ADR-6: build the dangling commit first, pin it, then review — the engine shells out to git
        // and needs a real reachable commit, not a free-floating tree.
        PublishIntent scratchIntent = new PublishIntent(-1L, ticket.ticketNo(), row.reviewRound(), row.treeHash(), row.baseCommit(),
                row.targetRef(), commitMessage(ticket, row), config.gateIdentity(), config.gateIdentity(),
                approvalStore.allocate(), null, PublishStatus.PENDING, null, null, clock.now(), null,
                clone, authFor(ticket));
        ObjectId dangling = commitPublisher.buildCommit(scratchIntent);
        commitPublisher.pinGateRef(clone, ticket.ticketNo(), row.reviewRound(), dangling);

        // No engine configured → the manual adapter speaks for the round. humanPass == null means
        // nobody has decided yet: the adapter emits "undecided" evidence (empty coverage) that the
        // policy's coverage invariant routes to REQUIRES_HUMAN — Fail-Closed (架构规范 I7), never a
        // guessed pass/reject and never a 4xx at the driver layer.
        //
        // An explicit humanPass is a human decision and always wins: the manual adapter speaks for
        // the round even when a gate-engine is configured. This is what makes 人工审查 (and the
        // NEEDS_HUMAN override) deterministic instead of silently re-running the engine.
        boolean humanDecided = command.humanPass() != null;
        boolean manualUndecided = !humanDecided && !config.engineConfigured();
        ReviewEngine engine = config.engineConfigured() && !humanDecided
                ? reviewEngineFactory.builtin()
                : reviewEngineFactory.forManualVerdict(command.humanPass(), command.note());
        if (engine == null) {
            // engineConfigured() was true but the factory returned no engine — fail-closed.
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "engine is configured but no built-in engine could be built (check engine.provider_id / provider credential)");
        }
        // Contract: review() never throws. Any failure is already an EngineFailure value.
        ReviewEvidence evidence = engine.review(new ReviewEngine.ReviewRequest(
                clone, ticket.ticketNo(), row.reviewRound(), snapshot, dangling));

        Decision decision = gatePolicy.decide(ticket.ticketNo(), row.reviewRound(), evidence, snapshot, config.policy());

        boolean coveredOk = evidence.accept(new EvidenceVisitor<Boolean>() {
            @Override
            public Boolean visit(EngineReport report) {
                return report.coveredPaths().containsAll(snapshot.changedPaths());
            }

            @Override
            public Boolean visit(EngineFailure failure) {
                return false;
            }
        });
        // An undecided manual round ran with neither engine nor human verdict — record it as a
        // degraded round. The report itself must stay degraded=false (a true flag auto-rejects under
        // the default engineAcceptDegraded=false policy); the handler owns this bookkeeping instead.
        boolean degraded = manualUndecided || evidence.accept(new EvidenceVisitor<Boolean>() {
            @Override
            public Boolean visit(EngineReport report) {
                return report.degraded();
            }

            @Override
            public Boolean visit(EngineFailure failure) {
                return true;
            }
        });

        String evidenceJson = EvidenceCodec.toJson(evidence);
        BlobRef findingsBlob = blobStore.put(evidenceJson.getBytes(StandardCharsets.UTF_8),
                "review/" + ticket.ticketNo() + "/" + row.reviewRound() + "/evidence.json");
        BlobRef rawBlob = evidence.accept(new EvidenceVisitor<BlobRef>() {
            @Override
            public BlobRef visit(EngineReport report) {
                return report.rawOutput();
            }

            @Override
            public BlobRef visit(EngineFailure failure) {
                return findingsBlob;
            }
        });

        var descriptor = engine.describe();
        long presubmitId = row.id();
        // 引擎基础设施故障（超时/崩溃/上游不可用）不是对代码的判决：判决仍按 REJECT 记录
        // （fail-closed，发布被阻断），但工单停留 PRESUBMITTED——用户可原地重试同一轮，
        // 而不是被迫重新预提审、无端消耗轮次。真正的代码驳回（含人工驳回）才进入 REJECTED。
        boolean engineFailed = evidence.accept(new EvidenceVisitor<Boolean>() {
            @Override
            public Boolean visit(EngineReport report) {
                return false;
            }

            @Override
            public Boolean visit(EngineFailure failure) {
                return true;
            }
        });
        TicketStage nextStage = switch (decision.verdict()) {
            case PASS -> TicketStage.READY_TO_PUBLISH;
            case REJECT -> engineFailed ? TicketStage.PRESUBMITTED : TicketStage.REJECTED;
            case REQUIRES_HUMAN -> TicketStage.NEEDS_HUMAN;
        };

        // P4 cost telemetry: bypass data — extracted from the engine's evidence, never affects the
        // verdict, never blocks publish (执行文档 §4 P4). A failure here yields an empty record
        // and the review proceeds normally; the metric basis is marked 'degraded'.
        String diffText = snapshot.diff();
        ReviewResultRepository.CostRecord cost;
        try {
            CostHint hint = engine.extractCost(evidence).orElse(CostHint.EMPTY);
            long diffBytesVal = diffText.getBytes(StandardCharsets.UTF_8).length;
            long diffLinesVal = diffText.chars().filter(c -> c == '\n').count();
            cost = new ReviewResultRepository.CostRecord(
                    hint.promptTokens(), hint.completionTokens(), hint.totalTokens(), hint.tokenSource(),
                    hint.reviewWallMs(), hint.llmWallMs(),
                    diffBytesVal, diffLinesVal);
        } catch (Exception e) {
            cost = ReviewResultRepository.CostRecord.EMPTY;
        }

        final ReviewResultRepository.CostRecord costFinal = cost;
        tx.inTransaction(() -> {
            reviewResults.insert(presubmitId, descriptor, decision.verdict(), findingsBlob, coveredOk, degraded,
                    rawBlob, clock.now(), costFinal);
            tickets.updateStage(ticket.ticketNo(), nextStage, clock.now());
            return null;
        });

        // decision.detail carries the structured basis (offending findings, missing paths, byte/line
        // numbers); without it the evidence chain can only show the bare reason. Bounded to 16
        // entries — it rides on the audit line, not a dedicated store.
        java.util.LinkedHashMap<String, String> reviewFields = new java.util.LinkedHashMap<>();
        reviewFields.put("tree", row.treeHash().hex());
        reviewFields.put("dangling", dangling.hex());
        reviewFields.put("engine", descriptor.engineId());
        reviewFields.put("reason", decision.reason());
        List<String> details = decision.detail();
        for (int i = 0; i < Math.min(details.size(), 16); i++) {
            reviewFields.put("detail." + i, details.get(i));
        }
        if (details.size() > 16) {
            reviewFields.put("detail.truncated", String.valueOf(details.size()));
        }
        auditLog.append(AuditEvent.of(clock.now(), "review." + decision.verdict().name().toLowerCase(java.util.Locale.ROOT),
                ticket.ticketNo(), row.reviewRound(), reviewFields));

        return new ReviewResult(ticket.ticketNo(), row.reviewRound(), row.treeHash().hex(), dangling.hex(),
                decision.verdict(), decision.reason(), decision.detail());
    }

    /** The ticket's project auth repo when bound, else the gate-level auth repo. */
    private RepoRef authFor(Ticket ticket) {
        return authResolver != null
                ? authResolver.forTicket(ticket).authRepo()
                : RepoRef.of(config.authRepo());
    }

    private static String commitMessage(Ticket ticket, PresubmitRepository.PresubmitRow row) {
        return ticket.ticketNo() + " round " + row.reviewRound() + "\n\n"
                + "Ticket: " + ticket.ticketNo() + "\n"
                + "Tree: " + row.treeHash().hex() + "\n"
                + "Reviewed-By: gate\n";
    }
}
