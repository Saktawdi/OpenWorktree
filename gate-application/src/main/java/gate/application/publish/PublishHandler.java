package gate.application.publish;

import gate.application.PublishCommand;
import gate.application.PublishResult;
import gate.application.ReconcileCommand;
import gate.application.ReconcileResult;
import gate.domain.audit.AuditEvent;
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
import gate.domain.review.ReviewEvidence;
import gate.domain.snapshot.Snapshot;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.ApprovalStore;
import gate.ports.AuditLog;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.AuthoritativeGitService;
import gate.ports.CommitPublisher;
import gate.ports.DbTransactionRunner;
import gate.ports.LockManager;
import gate.ports.PresubmitRepository;
import gate.ports.PublishIntentRepository;
import gate.ports.PublishProbe;
import gate.ports.RefObserver;
import gate.ports.ReviewResultRepository;
import gate.ports.SnapshotCapture;
import gate.ports.TicketRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publish capability handler (EX-002).
 * Owns PublishIntent, Git CAS, reconcile. Must enforce I1-I2-I5 invariants.
 * Extracted from GateServiceImpl to satisfy GOV-CPLX-001.
 */
public final class PublishHandler {
    private final GateConfig config;
    private final SnapshotCapture snapshotCapture;
    private final CommitPublisher commitPublisher;
    private final RefObserver refObserver;
    private final ApprovalStore approvalStore;
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
    private final PublishProbe publishProbe;
    private final AuthoritativeGitService authoritativeGitService;

    public PublishHandler(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                          RefObserver refObserver, ApprovalStore approvalStore, GatePolicy gatePolicy,
                          TicketRepository tickets, PresubmitRepository presubmits,
                          ReviewResultRepository reviewResults, PublishIntentRepository intents,
                          BlobStore blobStore, AuditLog auditLog, LockManager lockManager,
                          DbTransactionRunner tx, Clock clock, PublishProbe publishProbe) {
        this(config, snapshotCapture, commitPublisher, refObserver, approvalStore, gatePolicy, tickets, presubmits,
                reviewResults, intents, blobStore, auditLog, lockManager, tx, clock, publishProbe, null);
    }

    public PublishHandler(GateConfig config, SnapshotCapture snapshotCapture, CommitPublisher commitPublisher,
                          RefObserver refObserver, ApprovalStore approvalStore, GatePolicy gatePolicy,
                          TicketRepository tickets, PresubmitRepository presubmits,
                          ReviewResultRepository reviewResults, PublishIntentRepository intents,
                          BlobStore blobStore, AuditLog auditLog, LockManager lockManager,
                          DbTransactionRunner tx, Clock clock, PublishProbe publishProbe,
                          AuthoritativeGitService authoritativeGitService) {
        this.config = config;
        this.snapshotCapture = snapshotCapture;
        this.commitPublisher = commitPublisher;
        this.refObserver = refObserver;
        this.approvalStore = approvalStore;
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
        this.authoritativeGitService = authoritativeGitService;
    }

    public PublishResult handle(PublishCommand command) {
        Ticket ticket = tickets.find(command.ticketNo()).orElseThrow(
                () -> new GateException(GateErrorCode.USAGE, "no such ticket: " + command.ticketNo()));
        RepoRef clone = RepoRef.of(java.nio.file.Path.of(ticket.clonePath()));
        RepoRef auth = RepoRef.of(config.authRepo());
        String targetRef = ticket.targetRef();

        try (AutoCloseable ignored = lockManager.acquire(config.project(), targetRef)) {
            var presubmit = command.round() == null
                    ? presubmits.findLatest(ticket.ticketNo())
                    : presubmits.find(ticket.ticketNo(), command.round());
            var row = presubmit.orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                    "no presubmit round for " + ticket.ticketNo()));

            PublishAuthorization authorization = reauthorize(ticket, row, clone);

            Snapshot snapshot = rebuildSnapshot(clone, row);

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

        ObjectId commit = commitPublisher.buildCommit(intent);
        if (intent.commitSha() == null) {
            long id = intent.id();
            tx.inTransaction(() -> {
                intents.updateCommitSha(id, commit);
                return null;
            });
        } else if (!intent.commitSha().equals(commit)) {
            throw new GateException(GateErrorCode.INTERNAL,
                    "commit-tree was not deterministic: stored " + intent.commitSha().hex() + " rebuilt " + commit.hex());
        }
        intent = intent.withCommitSha(commit);

        commitPublisher.pinGateRef(clone, ticket.ticketNo(), row.reviewRound(), commit);

        publishProbe.at("AFTER_COMMIT_TREE");

        ApprovalGrant grant = intent.toGrant();
        if (!approvalStore.isLive(intent.approvalId()) && !approvalStore.isConsumed(intent.approvalId())) {
            approvalStore.issue(intent.approvalId(), grant);
        }

        // Phase3: authoritative CAS if available, otherwise fallback to direct push
        CommitPublisher.PublishOutcome outcome;
        boolean published;
        PublishStatus status;
        String refAfter;
        if (authoritativeGitService != null) {
            // CAS via authoritative service: validates expected_old_oid + nonce atomically
            ObjectId expectedOld = refObserver.tip(auth, targetRef).orElse(null);
            // Use expectedOld from intent's base? For Phase3 we use actual tip as expected
            // But intent stores baseCommit; CAS must use current tip per I2
            AuthoritativeGitService.CasResult cas = authoritativeGitService.casPublish(auth, targetRef, expectedOld, commit, row.treeHash(), authorization);
            outcome = new CommitPublisher.PublishOutcome(cas.success(), cas.success() ? 0 : 1, cas.reason(), cas.reason());
            publishProbe.at("AFTER_PUSH");
            refAfter = refToString(refObserver.tip(auth, targetRef));
            published = cas.success();
            if (cas.success() && cas.alreadyPublished()) {
                status = PublishStatus.PUBLISHED;
            } else if (cas.success()) {
                status = PublishStatus.PUBLISHED;
            } else if (cas.reason() != null && cas.reason().contains("CAS conflict")) {
                status = PublishStatus.REJECTED;
            } else if (cas.reason() != null && cas.reason().contains("nonce")) {
                status = PublishStatus.REJECTED;
            } else {
                status = PublishStatus.UNKNOWN;
            }
        } else {
            outcome = commitPublisher.publish(intent, authorization);
            publishProbe.at("AFTER_PUSH");
            refAfter = refToString(refObserver.tip(auth, targetRef));
            published = refObserver.published(auth, targetRef, commit);
            status = published ? PublishStatus.PUBLISHED : PublishStatus.PENDING;
        }
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
        if (intent.commitSha() != null && !approvalStore.isConsumed(intent.approvalId())
                && !approvalStore.isLive(intent.approvalId())) {
            approvalStore.reissue(intent.approvalId(), intent.toGrant());
        }
        return Optional.empty();
    }

    private Snapshot rebuildSnapshot(RepoRef clone, PresubmitRepository.PresubmitRow row) {
        byte[] diff = blobStore.get(new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256()));
        return snapshotCapture.rebuild(clone, row.targetRef(), row.treeHash(), row.baseCommit(),
                new String(diff, StandardCharsets.UTF_8));
    }

    private PublishAuthorization reauthorize(Ticket ticket, PresubmitRepository.PresubmitRow row, RepoRef clone) {
        var reviewRow = reviewResults.findLatestForPresubmit(row.id())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "ticket " + ticket.ticketNo() + " round " + row.reviewRound() + " has not been reviewed"));
        byte[] evidenceBytes = blobStore.get(new BlobRef(reviewRow.findingsBlobPath(),
                0L, "0".repeat(64)));
        String evidenceJson = new String(evidenceBytes, StandardCharsets.UTF_8);
        ReviewEvidence evidence = gate.application.EvidenceCodec.fromJson(evidenceJson,
                new BlobRef(reviewRow.rawBlobPath(), 0, "0".repeat(64)));
        Snapshot snapshot = rebuildSnapshot(clone, row);
        Decision decision = gatePolicy.decide(ticket.ticketNo(), row.reviewRound(), evidence, snapshot, config.policy());
        if (!decision.isPass()) {
            throw new GateException(GateErrorCode.REJECT_FINDINGS,
                    "cannot publish: latest review is " + decision.verdict() + " (" + decision.reason() + ")");
        }
        return decision.authorization();
    }

    private static String commitMessage(Ticket ticket, PresubmitRepository.PresubmitRow row) {
        return ticket.ticketNo() + " round " + row.reviewRound() + "\n\n"
                + "Ticket: " + ticket.ticketNo() + "\n"
                + "Tree: " + row.treeHash().hex() + "\n"
                + "Reviewed-By: gate\n";
    }

    private void audit(String kind, String ticketNo, Integer round, Map<String, String> fields) {
        auditLog.append(AuditEvent.of(clock.now(), kind, ticketNo, round, fields));
    }

    private static String refToString(Optional<ObjectId> tip) {
        return tip.map(ObjectId::hex).orElse("");
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }
}
