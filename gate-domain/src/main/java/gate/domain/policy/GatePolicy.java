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
                if (!report.treeHash().equals(snapshot.treeHash().hex())) {
                    return Decision.reject("evidence describes a different tree",
                            List.of("evidence tree=" + report.treeHash(),
                                    "snapshot tree=" + snapshot.treeHash().hex()));
                }
                if (report.degraded()) {
                    return Decision.rejectFailure(EngineFailure.FailureKind.CRASH,
                            "engine adapter reported degraded normalisation");
                }
                if (policy.requireCoverage()) {
                    List<String> missing = new ArrayList<>();
                    for (String changed : snapshot.changedPaths()) {
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
                return Decision.pass(
                        PublishAuthorization.mint(ticketNo, reviewRound, snapshot.treeHash(), report.engine()),
                        worst == null ? "no findings" : "worst severity " + worst);
            }

            @Override
            public Decision visit(EngineFailure failure) {
                return Decision.rejectFailure(failure.kind(), failure.detail());
            }
        });
    }

    private static boolean rejects(Severity severity, Policy.Strictness strictness) {
        return switch (strictness) {
            case BLOCKER_ONLY -> severity == Severity.BLOCKER;
            case BLOCKER_AND_WARNING -> severity == Severity.BLOCKER || severity == Severity.WARNING;
        };
    }
}
