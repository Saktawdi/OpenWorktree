package gate.application;

import gate.domain.blob.BlobRef;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.Decision;
import gate.domain.policy.GatePolicy;
import gate.domain.policy.PublishAuthorization;
import gate.domain.publish.ApprovalGrant;
import gate.domain.publish.ApprovalId;
import gate.domain.publish.PublishIntent;
import gate.domain.publish.PublishStatus;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.EngineFailure;
import gate.domain.review.ReviewEvidence;
import gate.domain.snapshot.CaptureIntegrityReport;
import gate.domain.snapshot.Snapshot;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.ApprovalStore;
import gate.ports.AuditLog;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.CommitPublisher;
import gate.ports.DbTransactionRunner;
import gate.ports.LockManager;
import gate.ports.PresubmitRepository;
import gate.ports.PublishIntentRepository;
import gate.ports.RefObserver;
import gate.ports.ReviewEngine;
import gate.ports.ReviewEngineFactory;
import gate.ports.ReviewResultRepository;
import gate.ports.SnapshotCapture;
import gate.ports.TicketRepository;
import gate.domain.audit.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single application service wiring the pipeline of 架构落地执行文档 §3.
 *
 * <p>Two structural rules dominate this class and are worth reading before the methods:
 *
 * <p><b>Order of durability on the publish path (§7.4).</b> The sequence is: write the
 * {@code publish_intent} row (durable, {@code synchronous=FULL}) → build the deterministic commit →
 * issue the approval → push → observe the ref → record the outcome. The intent is written before any
 * irreversible step so every crash point C0–C5 has a persistent anchor and recovery is unambiguous.
 *
 * <p><b>No SQLite transaction ever spans a git call (I3).</b> DB writes happen inside
 * {@code tx.inTransaction(...)} blocks that contain no {@link ProcessRunner}-backed work; git runs
 * strictly between those blocks. The {@link DbTransactionRunner} signature makes the wrong thing
 * unexpressible, and this class respects it by structure.
 *
 * <p>The class also never swallows an exception on the review path (执行文档 §8.3): any failure
 * surfaces as a {@link GateException} carrying an exit code, and the review engine's own failures are
 * already values ({@link EngineFailure}) that {@code GatePolicy} turns into rejects.
 */
public final class GateServiceImpl implements GateService {

    private final GateConfig config;
    private final SnapshotCapture snapshotCapture;
    private final CommitPublisher commitPublisher;
    private final RefObserver refObserver;
    private final ApprovalStore approvalStore;
    private final ReviewEngineFactory reviewEngineFactory;
    private final GatePolicy gatePolicy;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final ReviewResultRepository reviewResults;
    private final PublishIntentRepository intents;
    private final BlobStore blobStore;
    private final AuditLog auditLog;
    private final LockManager lockManager;
    private final DbTransactionRunner tx;
    private final Clock clock;
    private final gate.ports.PublishProbe publishProbe;

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, reviewEngineFactory, gatePolicy,
                tickets, presubmits, reviewResults, intents, blobStore, auditLog, lockManager, tx, clock,
                gate.ports.PublishProbe.NOOP);
    }

    public GateServiceImpl(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                           RefObserver refObserver, ApprovalStore approvalStore, ReviewEngineFactory reviewEngineFactory,
                           GatePolicy gatePolicy, TicketRepository tickets, PresubmitRepository presubmits,
                           ReviewResultRepository reviewResults, PublishIntentRepository intents, BlobStore blobStore,
                           AuditLog auditLog, LockManager lockManager, DbTransactionRunner tx, Clock clock,
                           gate.ports.PublishProbe publishProbe) {
        this.config = config;
        this.snapshotCapture = snapshotCapture;
        this.commitPublisher = commitPublisher;
        this.refObserver = refObserver;
        this.approvalStore = approvalStore;
        this.reviewEngineFactory = reviewEngineFactory;
        this.gatePolicy = gatePolicy;
        this.tickets = tickets;
        this.presubmits = presubmits;
        this.reviewResults = reviewResults;
        this.intents = intents;
        this.blobStore = blobStore;
        this.auditLog = auditLog;
        this.lockManager = lockManager;
        this.tx = tx;
        this.clock = clock;
        this.publishProbe = publishProbe;
    }

    // ---------------------------------------------------------------------------------------------
    // presubmit (T2 — the only transition an agent may trigger)
    // ---------------------------------------------------------------------------------------------

    @Override
    public PresubmitResult presubmit(PresubmitCommand command) {
        Ticket ticket = requireTicket(command.ticketNo());
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(ticket.clonePath()));
        RepoRef auth = RepoRef.of(config.authRepo());
        String targetRef = ticket.targetRef();

        // Capture is pure git + filesystem work; it holds no DB transaction (I3).
        Snapshot snapshot = snapshotCapture.capture(clone, auth, targetRef);

        if (snapshot.integrity().hasBlockers()) {
            audit("presubmit.blocked", ticket.ticketNo(), null, Map.of(
                    "reason", "capture_integrity",
                    "blockers", integrityText(snapshot.integrity())));
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "capture integrity blockers (no review round consumed): " + integrityText(snapshot.integrity()));
        }

        // Empty diff: refuse WITHOUT allocating a round (§3.2). commit-tree would not have caught it.
        if (snapshot.isEmptyDiff()) {
            audit("presubmit.emptyDiff", ticket.ticketNo(), null, Map.of(
                    "tree", snapshot.treeHash().hex(), "base_tree", snapshot.baseTree().hex()));
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "empty diff: tree == base tree; no review round consumed");
        }

        byte[] diffBytes = snapshot.diff().getBytes(StandardCharsets.UTF_8);
        // A round is allocated only now, after every validation passed. This is why an empty diff or
        // a blocked capture never consumes one.
        int round = presubmits.nextRound(ticket.ticketNo());
        BlobRef diffBlob = blobStore.put(diffBytes,
                "diff/" + ticket.ticketNo() + "/" + round + "/diff.patch");

        var row = tx.inTransaction(() -> {
            var inserted = presubmits.insert(ticket.ticketNo(), round, snapshot, diffBlob, clock.now());
            tickets.updateStage(ticket.ticketNo(), TicketStage.PRESUBMITTED, clock.now());
            return inserted;
        });

        audit("presubmit.ok", ticket.ticketNo(), round, Map.of(
                "tree", snapshot.treeHash().hex(),
                "base", snapshot.baseCommit().hex(),
                "diff_bytes", String.valueOf(diffBlob.bytes()),
                "diff_sha256", diffBlob.sha256(),
                "changed_paths", String.valueOf(snapshot.changedPaths().size())));

        return new PresubmitResult(ticket.ticketNo(), row.reviewRound(), snapshot.treeHash().hex(),
                snapshot.baseCommit().hex(), targetRef, diffBlob.bytes(), snapshot.changedPaths(),
                snapshot.integrity());
    }

    // ---------------------------------------------------------------------------------------------
    // review (verdict minted by GatePolicy, never by the agent)
    // ---------------------------------------------------------------------------------------------

    @Override
    public ReviewResult review(ReviewCommand command) {
        Ticket ticket = requireTicket(command.ticketNo());
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(ticket.clonePath()));

        var presubmit = command.round() == null
                ? presubmits.findLatest(ticket.ticketNo())
                : presubmits.find(ticket.ticketNo(), command.round());
        var row = presubmit.orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                "no presubmit round for " + ticket.ticketNo()
                        + (command.round() == null ? "" : "/" + command.round())));

        Snapshot snapshot = rebuildSnapshot(clone, row);

        // ADR-6: build the dangling commit first, pin it, then review — the engine shells out to git
        // and needs a real reachable commit, not a free-floating tree.
        PublishIntent scratchIntent = scratchIntentFor(ticket, row, clone);
        ObjectId dangling = commitPublisher.buildCommit(scratchIntent);
        commitPublisher.pinGateRef(clone, ticket.ticketNo(), row.reviewRound(), dangling);

        ReviewEngine engine = config.engineConfigured()
                ? reviewEngineFactory.forPrism()
                : reviewEngineFactory.forManualVerdict(command.humanPass(), command.note());
        if (engine == null) {
            // engineConfigured() was true but the factory returned no engine — fail-closed.
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "engine is configured but no prism engine could be built (check provider/.env)");
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
        boolean degraded = evidence.accept(new EvidenceVisitor<Boolean>() {
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
        TicketStage nextStage = switch (decision.verdict()) {
            case PASS -> TicketStage.READY_TO_PUBLISH;
            case REJECT -> TicketStage.REJECTED;
            case REQUIRES_HUMAN -> TicketStage.NEEDS_HUMAN;
        };

        // P4 cost telemetry: bypass data — extracted from the engine's evidence, never affects the
        // verdict, never blocks publish (执行文档 §4 P4). A failure here yields an empty record
        // and the review proceeds normally; the metric basis is marked 'degraded'.
        String diffText = snapshot.diff();
        gate.ports.ReviewResultRepository.CostRecord cost;
        try {
            gate.ports.CostHint hint = engine.extractCost(evidence).orElse(gate.ports.CostHint.EMPTY);
            long diffBytesVal = diffText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long diffLinesVal = diffText.chars().filter(c -> c == '\n').count();
            cost = new gate.ports.ReviewResultRepository.CostRecord(
                    hint.promptTokens(), hint.completionTokens(), hint.totalTokens(), hint.tokenSource(),
                    hint.reviewWallMs(), hint.llmWallMs(),
                    diffBytesVal, diffLinesVal);
        } catch (Exception e) {
            cost = gate.ports.ReviewResultRepository.CostRecord.EMPTY;
        }

        final gate.ports.ReviewResultRepository.CostRecord costFinal = cost;
        tx.inTransaction(() -> {
            reviewResults.insert(presubmitId, descriptor, decision.verdict(), findingsBlob, coveredOk, degraded,
                    rawBlob, clock.now(), costFinal);
            tickets.updateStage(ticket.ticketNo(), nextStage, clock.now());
            return null;
        });

        audit("review." + decision.verdict().name().toLowerCase(java.util.Locale.ROOT),
                ticket.ticketNo(), row.reviewRound(), Map.of(
                        "tree", row.treeHash().hex(),
                        "dangling", dangling.hex(),
                        "engine", descriptor.engineId(),
                        "reason", decision.reason()));

        return new ReviewResult(ticket.ticketNo(), row.reviewRound(), row.treeHash().hex(), dangling.hex(),
                decision.verdict(), decision.reason(), decision.detail());
    }

    // ---------------------------------------------------------------------------------------------
    // publish
    // ---------------------------------------------------------------------------------------------

    @Override
    public PublishResult publish(PublishCommand command) {
        Ticket ticket = requireTicket(command.ticketNo());
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(ticket.clonePath()));
        RepoRef auth = RepoRef.of(config.authRepo());
        String targetRef = ticket.targetRef();

        try (AutoCloseable ignored = lockManager.acquire(config.project(), targetRef)) {
            var presubmit = command.round() == null
                    ? presubmits.findLatest(ticket.ticketNo())
                    : presubmits.find(ticket.ticketNo(), command.round());
            var row = presubmit.orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                    "no presubmit round for " + ticket.ticketNo()));

            // The verdict is re-derived here, in this process, from the stored evidence. The DB is
            // not trusted as the authority for permission (§8.2): PublishAuthorization can only come
            // from GatePolicy, so publish re-runs the policy rather than reading a boolean.
            PublishAuthorization authorization = reauthorize(ticket, row, clone);

            Snapshot snapshot = rebuildSnapshot(clone, row);

            // TOCTOU (§3.4, A3): recompute the worktree tree now and compare to the reviewed tree.
            ObjectId recomputed = commitPublisher.recomputeTree(clone);
            if (!recomputed.equals(row.treeHash())) {
                tx.inTransaction(() -> {
                    tickets.updateStage(ticket.ticketNo(), TicketStage.PRESUBMITTED, clock.now());
                    return null;
                });
                audit("publish.toctou", ticket.ticketNo(), row.reviewRound(), Map.of(
                        "reviewed_tree", row.treeHash().hex(), "worktree_tree", recomputed.hex()));
                throw new GateException(GateErrorCode.REJECT_TOCTOU,
                        "worktree changed after review: reviewed " + row.treeHash().hex()
                                + " but worktree is now " + recomputed.hex() + "; re-run presubmit");
            }

            // Idempotency / recovery: an intent for this (ticket, round, tree) may already exist.
            Optional<PublishIntent> existing = intents.find(ticket.ticketNo(), row.reviewRound(), row.treeHash());
            if (existing.isPresent()) {
                Optional<PublishResult> replay = tryResume(existing.get(), auth, authorization);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }

            return runPublish(ticket, row, clone, auth, targetRef, authorization, existing.orElse(null));
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "publish failed: " + e.getMessage(), e);
        }
    }

    /**
     * The core publish sequence, in the order §7.4 requires.
     *
     * <ol>
     *   <li><b>Write the intent (durable) first.</b> {@code synchronous=FULL} means the row survives
     *       a crash before any irreversible step, giving every crash point C0–C5 an anchor.</li>
     *   <li>Build the deterministic commit and pin it. Same input ⇒ same SHA (I1), so a re-run after
     *       a kill rebuilds the identical object rather than a divergent one.</li>
     *   <li>Back-fill {@code commit_sha}, then issue the approval bound to
     *       {@code (ref, base, commit, tree)}.</li>
     *   <li>Push. Observe the ref. Record the outcome.</li>
     * </ol>
     *
     * Every DB write is its own transaction; none spans the git push (I3).
     */
    private PublishResult runPublish(Ticket ticket, PresubmitRepository.PresubmitRow row, RepoRef clone,
                                     RepoRef auth, String targetRef, PublishAuthorization authorization,
                                     PublishIntent existing) {
        String refBefore = refToString(refObserver.tip(auth, targetRef));

        PublishIntent intent = existing;
        if (intent == null) {
            ApprovalId approvalId = approvalStore.allocate();
            String message = commitMessage(ticket, row);
            PublishIntent toInsert = tx.inTransaction(() -> intents.insertPending(
                    ticket.ticketNo(), row.reviewRound(), rebuildSnapshot(clone, row), message,
                    config.gateIdentity(), config.gateIdentity(), approvalId, clock.now(), clone, auth));
            intent = toInsert;
        }

        // Deterministic commit (git work — no DB transaction open here).
        ObjectId commit = commitPublisher.buildCommit(intent);
        if (intent.commitSha() == null) {
            long id = intent.id();
            tx.inTransaction(() -> {
                intents.updateCommitSha(id, commit);
                return null;
            });
        } else if (!intent.commitSha().equals(commit)) {
            // Determinism is an invariant; a mismatch means identity/date were not pinned.
            throw new GateException(GateErrorCode.INTERNAL,
                    "commit-tree was not deterministic: stored " + intent.commitSha().hex() + " rebuilt " + commit.hex());
        }
        intent = intent.withCommitSha(commit);

        commitPublisher.pinGateRef(clone, ticket.ticketNo(), row.reviewRound(), commit);

        // C3 crash point: commit exists and is pinned, nothing pushed yet.
        publishProbe.at("AFTER_COMMIT_TREE");

        // Issue the one-shot approval bound to the exact ref transition.
        ApprovalGrant grant = intent.toGrant();
        if (!approvalStore.isLive(intent.approvalId()) && !approvalStore.isConsumed(intent.approvalId())) {
            approvalStore.issue(intent.approvalId(), grant);
        }

        CommitPublisher.PublishOutcome outcome = commitPublisher.publish(intent, authorization);
        // C4/C5 crash point: push has been sent (and may have landed), DB not yet updated.
        publishProbe.at("AFTER_PUSH");
        String refAfter = refToString(refObserver.tip(auth, targetRef));

        boolean published = refObserver.published(auth, targetRef, commit);
        PublishStatus status = published ? PublishStatus.PUBLISHED : PublishStatus.PENDING;
        long id = intent.id();
        tx.inTransaction(() -> {
            intents.updateOutcome(id, status, refBefore, refAfter, published ? clock.now() : null);
            if (published) {
                tickets.updateStage(ticket.ticketNo(), TicketStage.DONE, clock.now());
            }
            return null;
        });

        audit(published ? "publish.done" : "publish.pending", ticket.ticketNo(), row.reviewRound(), Map.of(
                "tree", row.treeHash().hex(),
                "commit", commit.hex(),
                "ref_before", refBefore,
                "ref_after", refAfter,
                "push_exit", String.valueOf(outcome.exitCode()),
                "push_accepted", String.valueOf(outcome.accepted())));

        if (!published) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "push did not land on " + targetRef + " (exit=" + outcome.exitCode() + "): "
                            + firstLine(outcome.stderr()) + "; state is PENDING, run reconcile");
        }

        return new PublishResult(ticket.ticketNo(), row.reviewRound(), row.treeHash().hex(), commit.hex(),
                targetRef, refBefore, refAfter, false);
    }

    /**
     * Fast path when an intent already exists: if its commit is already an ancestor of the target
     * ref, the publish already happened and this call is an idempotent replay (A4). Otherwise the
     * approval record is re-issued (a crash may have consumed or lost it) and the caller proceeds to
     * push again — the commit-tree is deterministic so no second commit is produced.
     */
    private Optional<PublishResult> tryResume(PublishIntent intent, RepoRef auth, PublishAuthorization authorization) {
        if (intent.commitSha() != null && refObserver.published(auth, intent.targetRef(), intent.commitSha())) {
            long id = intent.id();
            if (intent.status() != PublishStatus.PUBLISHED) {
                tx.inTransaction(() -> {
                    intents.updateOutcome(id, PublishStatus.PUBLISHED, intent.observedRefBefore(),
                            intent.commitSha().hex(), clock.now());
                    tickets.updateStage(intent.ticketNo(), TicketStage.DONE, clock.now());
                    return null;
                });
            }
            audit("publish.idempotent", intent.ticketNo(), intent.reviewRound(), Map.of(
                    "commit", intent.commitSha().hex(), "target", intent.targetRef()));
            return Optional.of(new PublishResult(intent.ticketNo(), intent.reviewRound(),
                    intent.treeHash().hex(), intent.commitSha().hex(), intent.targetRef(),
                    intent.observedRefBefore(), intent.commitSha().hex(), true));
        }
        // Not yet landed: re-issue the approval so the pending push can be retried by runPublish.
        if (intent.commitSha() != null && !approvalStore.isConsumed(intent.approvalId())
                && !approvalStore.isLive(intent.approvalId())) {
            approvalStore.reissue(intent.approvalId(), intent.toGrant());
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // reconcile — convergence is derived from auth.git, never from the DB (I4)
    // ---------------------------------------------------------------------------------------------

    @Override
    public ReconcileResult reconcile(ReconcileCommand command) {
        RepoRef auth = RepoRef.of(config.authRepo());
        List<PublishIntent> pending = command.ticketNo() == null
                ? intents.findPending()
                : intents.findByTicket(command.ticketNo()).stream()
                        .filter(i -> i.status() == PublishStatus.PENDING).toList();

        List<ReconcileResult.IntentOutcome> outcomes = new ArrayList<>();
        for (PublishIntent intent : pending) {
            String from = intent.status().name();
            if (intent.commitSha() == null) {
                // C0/C1: nothing irreversible happened. Abandon so a fresh presubmit can re-run.
                long id = intent.id();
                tx.inTransaction(() -> {
                    intents.updateOutcome(id, PublishStatus.ABANDONED, intent.observedRefBefore(),
                            intent.observedRefAfter(), clock.now());
                    return null;
                });
                outcomes.add(new ReconcileResult.IntentOutcome(intent.id(), intent.ticketNo(), intent.reviewRound(),
                        intent.treeHash().hex(), null, from, PublishStatus.ABANDONED.name(),
                        "no commit built; safe to re-presubmit"));
                continue;
            }
            boolean published = refObserver.published(auth, intent.targetRef(), intent.commitSha());
            if (published) {
                long id = intent.id();
                tx.inTransaction(() -> {
                    intents.updateOutcome(id, PublishStatus.PUBLISHED, intent.observedRefBefore(),
                            intent.commitSha().hex(), clock.now());
                    tickets.updateStage(intent.ticketNo(), TicketStage.DONE, clock.now());
                    return null;
                });
                outcomes.add(new ReconcileResult.IntentOutcome(intent.id(), intent.ticketNo(), intent.reviewRound(),
                        intent.treeHash().hex(), intent.commitSha().hex(), from, PublishStatus.PUBLISHED.name(),
                        "commit is ancestor of target; converged to PUBLISHED"));
            } else {
                // Commit exists but did not land. If the base still matches, the push can be retried;
                // if the base moved, the intent is stale and must be abandoned. Either way the DB
                // never fabricated a publish that git does not reflect.
                boolean baseIntact = refObserver.tip(auth, intent.targetRef())
                        .map(tip -> tip.equals(intent.baseCommit())).orElse(false);
                outcomes.add(new ReconcileResult.IntentOutcome(intent.id(), intent.ticketNo(), intent.reviewRound(),
                        intent.treeHash().hex(), intent.commitSha().hex(), from, PublishStatus.PENDING.name(),
                        baseIntact ? "commit not landed, base intact; publish is safely retryable"
                                : "commit not landed, base moved; re-presubmit required"));
            }
            audit("reconcile", intent.ticketNo(), intent.reviewRound(), Map.of(
                    "commit", intent.commitSha().hex(),
                    "published", String.valueOf(published)));
        }
        return new ReconcileResult(outcomes);
    }

    // ---------------------------------------------------------------------------------------------
    // status — read-only projection
    // ---------------------------------------------------------------------------------------------

    @Override
    public StatusResult status(StatusQuery query) {
        RepoRef auth = RepoRef.of(config.authRepo());
        String targetRef = config.primaryTargetRef();
        // A gate whose auth repo directory does not exist yet (fresh deploy, before init/reconcile)
        // must still serve a status snapshot: git cannot even start in a missing cwd, which would
        // escalate to GATE_ERROR_IO and fail the whole projection. Degrade to empty auth facts.
        boolean authPresent = Files.exists(config.authRepo());
        String authTip = authPresent ? refToString(refObserver.tip(auth, targetRef)) : null;
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
                    publishedInAuth = authPresent && intent.get().commitSha() != null
                            && refObserver.published(auth, intent.get().targetRef(), intent.get().commitSha());
                }
            }
            statuses.add(new StatusResult.TicketStatus(t.ticketNo(), t.stage().name(), round, tree,
                    intentStatus, commitSha, publishedInAuth));
        }
        return new StatusResult(targetRef, authTip, count, statuses);
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private Ticket requireTicket(String ticketNo) {
        return tickets.find(ticketNo).orElseThrow(
                () -> new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo));
    }

    private Snapshot rebuildSnapshot(RepoRef clone, PresubmitRepository.PresubmitRow row) {
        byte[] diff = blobStore.get(new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256()));
        return snapshotCapture.rebuild(clone, row.targetRef(), row.treeHash(), row.baseCommit(),
                new String(diff, StandardCharsets.UTF_8));
    }

    /**
     * A throwaway intent used only to build the dangling commit for review (ADR-6). It is never
     * persisted; the deterministic commit-tree means this produces exactly the SHA that publish will
     * later rebuild and push.
     */
    private PublishIntent scratchIntentFor(Ticket ticket, PresubmitRepository.PresubmitRow row, RepoRef clone) {
        return new PublishIntent(-1L, ticket.ticketNo(), row.reviewRound(), row.treeHash(), row.baseCommit(),
                row.targetRef(), commitMessage(ticket, row), config.gateIdentity(), config.gateIdentity(),
                approvalStore.allocate(), null, PublishStatus.PENDING, null, null, clock.now(), null,
                clone, RepoRef.of(config.authRepo()));
    }

    /**
     * Re-derives publish authority in this process by replaying the stored evidence through
     * {@code GatePolicy}. The DB is not trusted to say "this was approved" — only the policy can mint
     * a {@link PublishAuthorization}, so publishing without a genuine PASS is unreachable (§8.2).
     */
    private PublishAuthorization reauthorize(Ticket ticket, PresubmitRepository.PresubmitRow row, RepoRef clone) {
        var reviewRow = reviewResults.findLatestForPresubmit(row.id())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "ticket " + ticket.ticketNo() + " round " + row.reviewRound() + " has not been reviewed"));
        byte[] evidenceBytes = blobStore.get(new BlobRef(reviewRow.findingsBlobPath(),
                sizeOf(reviewRow.findingsBlobPath()), sha256Placeholder(reviewRow.findingsBlobPath())));
        String evidenceJson = new String(evidenceBytes, StandardCharsets.UTF_8);
        ReviewEvidence evidence = EvidenceCodec.fromJson(evidenceJson,
                new BlobRef(reviewRow.rawBlobPath(), 0, "0".repeat(64)));
        Snapshot snapshot = rebuildSnapshot(clone, row);
        Decision decision = gatePolicy.decide(ticket.ticketNo(), row.reviewRound(), evidence, snapshot, config.policy());
        if (!decision.isPass()) {
            throw new GateException(GateErrorCode.REJECT_FINDINGS,
                    "cannot publish: latest review is " + decision.verdict() + " (" + decision.reason() + ")");
        }
        return decision.authorization();
    }

    private String commitMessage(Ticket ticket, PresubmitRepository.PresubmitRow row) {
        // Trailers are declarations, not proof (I5): a same-privilege agent could forge them, so the
        // gate never trusts them — they exist for human/audit readability only.
        return ticket.ticketNo() + " round " + row.reviewRound() + "\n\n"
                + "Ticket: " + ticket.ticketNo() + "\n"
                + "Tree: " + row.treeHash().hex() + "\n"
                + "Reviewed-By: gate\n";
    }

    private void audit(String kind, String ticketNo, Integer round, Map<String, String> fields) {
        auditLog.append(AuditEvent.of(clock.now(), kind, ticketNo, round, fields));
    }

    private static String integrityText(CaptureIntegrityReport report) {
        return report.blockers().stream()
                .map(v -> v.rule() + ":" + v.detail())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private static String refToString(Optional<ObjectId> tip) {
        return tip.map(ObjectId::hex).orElse("");
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }

    // The findings blob was written by this same process via BlobStore.put, which computed and
    // returned a BlobRef; but review_result only persisted the relative path. get() re-reads by path
    // and the store validates nothing else, so size/sha are not needed for retrieval — the store
    // resolves purely by relPath. These helpers keep BlobRef construction honest without a DB round
    // trip for values retrieval does not use.
    private long sizeOf(String relPath) {
        return 0L;
    }

    private String sha256Placeholder(String relPath) {
        return "0".repeat(64);
    }
}
