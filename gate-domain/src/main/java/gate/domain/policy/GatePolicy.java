package gate.domain.policy;

import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.domain.snapshot.Snapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * The single place a verdict is produced (架构落地执行文档 §5.3 constraint 1, §8.2).
 *
 * <p>Engines return <em>evidence</em>. An engine self-reporting {@code {"verdict":"pass"}} is
 * data, not authority. Only this class mints {@link PublishAuthorization}.
 *
 * <p>Every branch defaults to reject:
 * <ul>
 *   <li>{@link EngineFailure} of any kind → reject (the whole point of the sealed hierarchy);</li>
 *   <li>{@code degraded} → reject, because the adapter admits it could not fully normalise;</li>
 *   <li>coverage gap → reject, closing the "engine silently skipped a changed file" hole;</li>
 *   <li>a report describing a <em>different</em> tree → reject, so stale evidence cannot be
 *       replayed against a newer snapshot;</li>
 *   <li>unknown severity is already mapped to BLOCKER by the adapter, so it lands in the reject
 *       branch here without this class needing a special case.</li>
 * </ul>
 */
public final class GatePolicy {

    /**
     * @param ticketNo    ticket this evidence belongs to
     * @param reviewRound round this evidence belongs to
     */
    public Decision decide(String ticketNo, int reviewRound, ReviewEvidence evidence, Snapshot snapshot, Policy policy) {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (evidence == null || snapshot == null || policy == null) {
            throw new IllegalArgumentException("evidence/snapshot/policy must not be null");
        }
        return evidence.accept(new EvidenceVisitor<Decision>() {

            @Override
            public Decision visit(EngineReport report) {
                // Skipped paths shrink the coverage denominator — but only the reasons the project
                // (or the medium itself) authorises. A `too_large` skip means real code went
                // unreviewed without anyone blessing it, so it routes to REQUIRES_HUMAN exactly
                // like a plain coverage gap; the others (binary/secret/rule/deleted) are recorded
                // for the evidence chain but do not block. Every skip surfaces in the decision
                // detail — nothing ever leaves the denominator silently.
                List<String> skippedDetail = new ArrayList<>();
                for (gate.domain.review.SkippedPath sp : report.skippedPaths()) {
                    skippedDetail.add(sp.reason() + " " + sp.path());
                }
                Decision decision = decideReport(report, snapshot, policy, ticketNo, reviewRound);
                return skippedDetail.isEmpty() ? decision : decision.withExtraDetail(skippedDetail);
            }

            @Override
            public Decision visit(EngineFailure failure) {
                return Decision.rejectFailure(failure.kind(), failure.detail());
            }
        });
    }

    /**
     * The report-to-decision logic proper, extracted so {@link #decide} can decorate the outcome
     * with the skipped-path ledger without threading it through every return. Every branch still
     * defaults to reject; the skip handling only ever <em>widens</em> the coverage denominator by
     * the explicitly authorised reasons and routes {@code too_large} to a human.
     */
    private Decision decideReport(EngineReport report, Snapshot snapshot, Policy policy,
                                  String ticketNo, int reviewRound) {
        if (!report.treeHash().equals(snapshot.treeHash().hex())) {
            return Decision.reject("evidence describes a different tree",
                    List.of("evidence tree=" + report.treeHash(),
                            "snapshot tree=" + snapshot.treeHash().hex()));
        }
        if (report.degraded() && !policy.engineAcceptDegraded()) {
            return Decision.rejectFailure(EngineFailure.FailureKind.CRASH,
                    "engine adapter reported degraded normalisation");
        }
        java.util.Map<String, String> skippedByPath = new java.util.LinkedHashMap<>();
        List<String> tooLarge = new ArrayList<>();
        for (gate.domain.review.SkippedPath sp : report.skippedPaths()) {
            skippedByPath.put(sp.path(), sp.reason());
            if (!gate.domain.review.SkippedPath.allowedWithoutHuman(sp.reason())) {
                tooLarge.add(sp.path());
            }
        }
        if (policy.requireCoverage() && !tooLarge.isEmpty()) {
            return Decision.needsHuman("per-file token gate skipped real code before review",
                    tooLarge);
        }
        if (policy.requireCoverage()) {
            List<String> missing = new ArrayList<>();
            for (String changed : snapshot.changedPaths()) {
                if (skippedByPath.containsKey(changed)) {
                    continue;   // authorised skip — subtracted from the denominator
                }
                if (!report.coveredPaths().contains(changed)) {
                    missing.add(changed);
                }
            }
            if (!missing.isEmpty()) {
                return Decision.needsHuman("coverage gap: engine did not review every changed path", missing);
            }
        }
        if (snapshot.diff().length() > policy.maxDiffBytes()) {
            return Decision.needsHuman("diff exceeds configured byte limit",
                    List.of("bytes=" + snapshot.diff().length(), "limit=" + policy.maxDiffBytes()));
        }
        long lines = snapshot.diff().lines().count();
        if (lines > policy.maxDiffLines()) {
            return Decision.needsHuman("diff exceeds configured line limit",
                    List.of("lines=" + lines, "limit=" + policy.maxDiffLines()));
        }

        Severity worst = null;
        List<String> offending = new ArrayList<>();
        for (Finding f : report.findings()) {
            worst = Decision.worst(worst, f.severity());
            if (rejects(f.severity(), policy.strictness())) {
                offending.add(f.severity() + " " + f.path()
                        + (f.lineStart() == null ? "" : ":" + f.lineStart()) + " " + f.message());
            }
        }
        if (!offending.isEmpty()) {
            return Decision.reject("findings at or above configured strictness", offending);
        }
        // Bound authorization: bind ref + old OID + expiry + nonce (ADR-003 §7.1)
        // Fail-closed: if binding data missing, refuse to mint rather than falling back to unbound token
        if (snapshot.targetRef() == null || snapshot.targetRef().isBlank() || snapshot.baseCommit() == null) {
            return Decision.reject("cannot mint authorization: missing targetRef or baseCommit binding",
                    List.of("targetRef=" + snapshot.targetRef(), "baseCommit=" + snapshot.baseCommit()));
        }
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant exp = now.plusSeconds(3600);
        String nonce = java.util.UUID.randomUUID().toString();
        PublishAuthorization auth = PublishAuthorization.mint(ticketNo, reviewRound, snapshot.treeHash(), report.engine(),
                snapshot.targetRef(), snapshot.baseCommit(), now, exp, nonce);
        return Decision.pass(auth, worst == null ? "no findings" : "worst severity " + worst);
    }

    private static boolean rejects(Severity severity, Policy.Strictness strictness) {
        return switch (strictness) {
            case BLOCKER_ONLY -> severity == Severity.BLOCKER;
            case BLOCKER_AND_WARNING -> severity == Severity.BLOCKER || severity == Severity.WARNING;
        };
    }
}
