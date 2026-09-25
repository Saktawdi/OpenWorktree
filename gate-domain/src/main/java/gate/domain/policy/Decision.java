package gate.domain.policy;

import gate.domain.review.EngineFailure;
import gate.domain.review.Severity;
import java.util.List;

/**
 * The outcome of {@link GatePolicy}. A decision is either ALLOW — and then it carries the only
 * mintable {@link PublishAuthorization} — or it is not.
 */
public record Decision(
        Verdict verdict,
        String reason,
        List<String> detail,
        PublishAuthorization authorization) {

    public Decision {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        detail = List.copyOf(detail);
        if (verdict == Verdict.PASS && authorization == null) {
            throw new IllegalStateException("PASS decision must carry an authorization");
        }
        if (verdict != Verdict.PASS && authorization != null) {
            throw new IllegalStateException("non-PASS decision must not carry an authorization");
        }
    }

    static Decision pass(PublishAuthorization auth, String reason) {
        return new Decision(Verdict.PASS, reason, List.of(), auth);
    }

    static Decision reject(String reason, List<String> detail) {
        return new Decision(Verdict.REJECT, reason, detail, null);
    }

    static Decision rejectFailure(EngineFailure.FailureKind kind, String detail) {
        return new Decision(Verdict.REJECT, "engine failure: " + kind, List.of(detail), null);
    }

    static Decision needsHuman(String reason, List<String> detail) {
        return new Decision(Verdict.REQUIRES_HUMAN, reason, detail, null);
    }

    /**
     * Audit decoration: prepends extra detail lines (e.g. the skipped-path ledger) to an existing
     * decision without touching its verdict, reason or authorization. The verdict stays exactly
     * what {@link GatePolicy} minted — decoration is bookkeeping for the evidence chain, never a
     * channel that could flip a decision.
     */
    public Decision withExtraDetail(List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        List<String> merged = new java.util.ArrayList<>(extra);
        merged.addAll(detail);
        return new Decision(verdict, reason, merged, authorization);
    }

    public boolean isPass() {
        return verdict == Verdict.PASS;
    }

    public enum Verdict {
        PASS,
        REJECT,
        REQUIRES_HUMAN
    }

    /** Worst severity seen, exposed for the audit trail / severity histogram (§8.4 item 8). */
    public static Severity worst(Severity a, Severity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
