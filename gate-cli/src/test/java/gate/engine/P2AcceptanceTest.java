package gate.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.application.presubmit.PresubmitCommand;
import gate.application.publish.PublishCommand;
import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.domain.blob.BlobRef;
import gate.domain.git.RepoRef;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.policy.Decision;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.ReviewResultRepository;
import gate.testkit.GateHarness;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P2 acceptance A6 / A8 (架构落地执行文档 §4 P2).
 *
 * <p>A6: a blocker finding auto-rejects, and the authoritative target branch HEAD does not move.
 * This exercises the GatePolicy {@code finding → REJECT} branch via the manual engine (whose
 * coveredPaths == changedPaths and degraded == false), so the reject is provably from the finding
 * itself, not from a degraded/coverage path. gate-engine walks the same {@code decide()} — that is the
 * "adding the built-in engine costs zero core changes" guarantee, verified by BuiltinReviewEngineTest.
 *
 * <p>A8: the rejected review's evidence is persisted as a structured findings blob carrying
 * file / line / severity / message, exportable for feeding back to the agent.
 */
class P2AcceptanceTest {

    private GateHarness h;

    @BeforeEach
    void setUp() {
        h = new GateHarness();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    /** A6: a blocker diff is auto-rejected and the authoritative tip is unchanged. */
    @Test
    void a6_blockerFindingAutoRejectsAndTipUnchanged() {
        RepoRef clone = h.createTicket("T-A6");
        h.writeFile(clone, "feature.txt", "a real change\n");
        h.service().presubmit(new PresubmitCommand("T-A6"));

        String tipBefore = h.authTip();
        long countBefore = h.authCommitCount();

        // Manual reject carries a BLOCKER finding through the ordinary engine contract.
        ReviewResult r = h.service().review(
                new ReviewCommand("T-A6", null, false, "deliberate blocker: unreviewed SQL concatenation"));
        assertEquals(Decision.Verdict.REJECT, r.verdict(), "a BLOCKER finding must auto-reject");
        assertTrue(r.reason().contains("findings"), "reject reason must cite findings");
        assertFalse(r.detail().isEmpty(), "reject must carry finding detail for feedback");

        // The authoritative target branch must not move on a reject.
        assertEquals(tipBefore, h.authTip(), "tip must not move on a reject (A6)");
        assertEquals(countBefore, h.authCommitCount(), "no commit must land on a reject");

        // And publishing the rejected round must also fail (reauthorize re-derives the verdict).
        GateException ex = org.junit.jupiter.api.Assertions.assertThrows(GateException.class,
                () -> h.service().publish(new PublishCommand("T-A6", null)));
        assertEquals(GateErrorCode.REJECT_FINDINGS, ex.code(), "publish must re-derive REJECT from stored evidence");
        assertEquals(tipBefore, h.authTip(), "tip must still not move after a publish attempt");
    }

    /**
     * A8: the rejected review's findings blob is structured and exportable, carrying file / line /
     * severity / message.
     */
    @Test
    void a8_rejectedEvidenceExportableAsStructuredFindings() {
        RepoRef clone = h.createTicket("T-A8");
        h.writeFile(clone, "vuln.py", "query = 'SELECT * FROM u WHERE name=' + name\n");
        h.service().presubmit(new PresubmitCommand("T-A8"));
        h.service().review(new ReviewCommand("T-A8", null, false, "SQL injection in vuln.py"));

        PresubmitRepository.PresubmitRow ps = h.presubmits().findLatest("T-A8")
                .orElseThrow(() -> new AssertionError("no presubmit for T-A8"));
        ReviewResultRepository.ReviewResultRow rr = h.reviewResults().findLatestForPresubmit(ps.id())
                .orElseThrow(() -> new AssertionError("no review_result for presubmit " + ps.id()));

        // The findings blob is the evidence JSON, re-readable and structured.
        byte[] findingsBytes = h.blobStore().get(new BlobRef(rr.findingsBlobPath(), 0, "0".repeat(64)));
        String findings = new String(findingsBytes, StandardCharsets.UTF_8);

        // A8 requires file / line / severity / message — all four must be present.
        assertTrue(findings.contains("\"severity\""), "findings blob must carry severity");
        assertTrue(findings.contains("\"path\""), "findings blob must carry file path");
        assertTrue(findings.contains("\"line_start\""), "findings blob must carry line");
        assertTrue(findings.contains("\"message\""), "findings blob must carry message");

        // The stored verdict matches the reject decision.
        assertEquals(Decision.Verdict.REJECT, rr.verdict(), "stored verdict must be REJECT");
        assertFalse(rr.degraded(), "manual reject is not degraded");

        // The raw blob must also exist for full provenance export.
        byte[] rawBytes = h.blobStore().get(new BlobRef(rr.rawBlobPath(), 0, "0".repeat(64)));
        assertTrue(rawBytes.length > 0, "raw blob must be exportable");
    }

    /** A8 supplement: a PASS review also exports a structured (empty-findings) blob. */
    @Test
    void a8_passReviewAlsoExportsStructuredBlob() {
        RepoRef clone = h.createTicket("T-A8P");
        h.writeFile(clone, "ok.txt", "clean change\n");
        h.service().presubmit(new PresubmitCommand("T-A8P"));
        h.service().review(new ReviewCommand("T-A8P", null, true, null));

        PresubmitRepository.PresubmitRow ps = h.presubmits().findLatest("T-A8P")
                .orElseThrow(() -> new AssertionError("no presubmit for T-A8P"));
        ReviewResultRepository.ReviewResultRow rr = h.reviewResults().findLatestForPresubmit(ps.id())
                .orElseThrow(() -> new AssertionError("no review_result"));
        byte[] findingsBytes = h.blobStore().get(new BlobRef(rr.findingsBlobPath(), 0, "0".repeat(64)));
        String findings = new String(findingsBytes, StandardCharsets.UTF_8);

        assertTrue(findings.contains("\"findings\":[]"), "pass with no findings exports an empty findings array");
        assertTrue(findings.contains("\"covered_paths\""), "evidence blob must carry coverage");
        assertEquals(Decision.Verdict.PASS, rr.verdict());
    }
}
