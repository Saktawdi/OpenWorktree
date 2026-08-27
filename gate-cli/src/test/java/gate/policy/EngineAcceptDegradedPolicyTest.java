package gate.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.blob.BlobRef;
import gate.domain.git.ObjectId;
import gate.domain.policy.Decision;
import gate.domain.policy.GatePolicy;
import gate.domain.policy.Policy;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineReport;
import gate.domain.review.Finding;
import gate.domain.review.Severity;
import gate.domain.snapshot.CaptureIntegrityReport;
import gate.domain.snapshot.Snapshot;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Validates the {@code policy.engineAcceptDegraded} escape hatch
 * (docs/archive/prism-schema-validation.md).
 *
 * <p>prism's JSON has no "files actually reviewed" field, so the adapter always raises the
 * {@code degraded} flag on a successful parse. The production default
 * ({@link Policy#defaults()} → {@code engineAcceptDegraded=false}) keeps the fail-closed
 * behaviour: a degraded report is rejected by {@link GatePolicy} regardless of findings.
 *
 * <p>This test pins the <em>other</em> branch — the H1 data-collection escape hatch — where
 * {@code engineAcceptDegraded=true} lets a degraded-but-otherwise-clean report through to PASS.
 * It is the single test that exercises the new code path end-to-end through {@code decide()},
 * since {@code BuiltinReviewEngineTest} only checks the adapter sets {@code degraded=true} and the
 * acceptance tests ({@code AcceptanceTest} / {@code P2AcceptanceTest}) use the manual engine,
 * which never degrades.
 *
 * <p>Both directions are covered here so a future refactor that flips the polarity by mistake
 * fails loudly:
 * <ol>
 *   <li>degraded report + {@code engineAcceptDegraded=false} → REJECT (production default);</li>
 *   <li>degraded report + {@code engineAcceptDegraded=true}  → PASS   (H1 escape hatch).</li>
 * </ol>
 * The non-degraded case stays PASS under both settings (the flag only relaxes the degraded
 * branch, it never tightens anything else) — asserted as a guard.
 */
class EngineAcceptDegradedPolicyTest {

    private static final ObjectId TREE =
            ObjectId.of("1111111111111111111111111111111111111111");
    private static final ObjectId BASE =
            ObjectId.of("2222222222222222222222222222222222222222");
    private static final ObjectId BASE_TREE =
            ObjectId.of("3333333333333333333333333333333333333333");
    private static final EngineDescriptor ENGINE = new EngineDescriptor(
            "prism", "0.5.0", "fingerprint", "newapi", "test-model");
    private static final BlobRef RAW = new BlobRef("raw/T-1/1/prism.json", 0, "0".repeat(64));

    /** Production default: a degraded report is rejected even with zero findings. */
    @Test
    void degradedReport_rejectedUnderProductionDefault() {
        Snapshot snapshot = snapshot();
        EngineReport degraded = report(true, List.of());

        GatePolicy gate = new GatePolicy();
        Policy failClosed = Policy.defaults();   // engineAcceptDegraded=false

        Decision d = gate.decide("T-1", 1, degraded, snapshot, failClosed);
        assertEquals(Decision.Verdict.REJECT, d.verdict(),
                "production default must reject a degraded report (fail-closed)");
        assertFalse(d.isPass(), "no authorization may be minted for a degraded report under defaults");
        assertTrue(d.reason().contains("degraded") || d.detail().stream().anyMatch(s -> s.contains("degraded")),
                "reject reason must explain the degraded normalisation: " + d.reason());
    }

    /** H1 escape hatch: same degraded report passes when the operator opts in. */
    @Test
    void degradedReport_passesWhenEngineAcceptDegradedTrue() {
        Snapshot snapshot = snapshot();
        EngineReport degraded = report(true, List.of());

        GatePolicy gate = new GatePolicy();
        Policy accept = new Policy(Policy.Strictness.BLOCKER_ONLY, true,
                2_000_000L, 20_000L, true);   // engineAcceptDegraded=true

        Decision d = gate.decide("T-1", 1, degraded, snapshot, accept);
        assertEquals(Decision.Verdict.PASS, d.verdict(),
                "engineAcceptDegraded=true must let a clean-but-degraded report through");
        assertTrue(d.isPass(), "PASS must carry a PublishAuthorization");
        assertEquals("T-1", d.authorization().ticketNo(), "authorization must bind the ticket");
        assertEquals(1, d.authorization().reviewRound(), "authorization must bind the round");
        assertEquals(TREE, d.authorization().treeHash(), "authorization must bind the tree");
    }

    /** The flag only relaxes the degraded branch — a non-degraded report passes under both. */
    @Test
    void nonDegradedReport_passesUnderBothSettings() {
        Snapshot snapshot = snapshot();
        EngineReport clean = report(false, List.of());

        GatePolicy gate = new GatePolicy();
        Policy failClosed = Policy.defaults();
        Policy accept = new Policy(Policy.Strictness.BLOCKER_ONLY, true, 2_000_000L, 20_000L, true);

        assertEquals(Decision.Verdict.PASS,
                gate.decide("T-1", 1, clean, snapshot, failClosed).verdict(),
                "non-degraded must pass under the production default");
        assertEquals(Decision.Verdict.PASS,
                gate.decide("T-1", 1, clean, snapshot, accept).verdict(),
                "non-degraded must pass under the escape hatch too");
    }

    /** The escape hatch does NOT override a BLOCKER finding — only the degraded branch. */
    @Test
    void degradedReportWithBlocker_stillRejectsEvenWhenAcceptDegradedTrue() {
        Snapshot snapshot = snapshot();
        EngineReport degradedWithBlocker = report(true, List.of(
                new Finding(Severity.BLOCKER, "high", "app.py", 2, 2,
                        "sql-inj", "SQL injection", "parameterize")));

        GatePolicy gate = new GatePolicy();
        Policy accept = new Policy(Policy.Strictness.BLOCKER_ONLY, true, 2_000_000L, 20_000L, true);

        Decision d = gate.decide("T-1", 1, degradedWithBlocker, snapshot, accept);
        assertEquals(Decision.Verdict.REJECT, d.verdict(),
                "engineAcceptDegraded=true is not a free pass — a BLOCKER finding still rejects");
        assertFalse(d.isPass());
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static Snapshot snapshot() {
        return new Snapshot(TREE, BASE, BASE_TREE, "refs/heads/main",
                List.of("app.py"), "diff body", CaptureIntegrityReport.clean());
    }

    /**
     * An {@link EngineReport} that matches the snapshot's treeHash and covers every changed path,
     * so the only varying axis is {@code degraded} and (optionally) findings.
     */
    private static EngineReport report(boolean degraded, List<Finding> findings) {
        return new EngineReport(ENGINE, TREE.hex(), findings,
                Set.of("app.py"), degraded, RAW, 0, Duration.ZERO);
    }
}
