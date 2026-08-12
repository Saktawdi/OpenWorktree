package gate.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.application.PresubmitCommand;
import gate.application.PublishCommand;
import gate.application.PublishResult;
import gate.application.ReviewCommand;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.testkit.GateHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A1/A3/A4 acceptance behaviours through the real service (架构落地执行文档 §4 P1).
 *
 * <p>A5 (crash recovery via a hard kill) is a separate test that spawns a child JVM, since a
 * precise kill cannot be simulated in-process.
 */
class AcceptanceTest {

    private GateHarness h;

    @BeforeEach
    void setUp() {
        h = new GateHarness();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    /** A1: a full run adds exactly ONE commit on top of the seeded base (rev-list count == base+1). */
    @Test
    void a1_fullRunAddsExactlyOneCommit() {
        long before = h.authCommitCount();
        String baseTip = h.authTip();

        RepoRef clone = h.createTicket("TICKET-1");
        h.writeFile(clone, "feature.txt", "the feature\n");
        h.service().presubmit(new PresubmitCommand("TICKET-1"));
        h.service().review(new ReviewCommand("TICKET-1", null, true, null));
        PublishResult r = h.service().publish(new PublishCommand("TICKET-1", null));

        assertEquals(before + 1, h.authCommitCount(),
                "the ticket must add exactly one commit on top of the bootstrap base (A1)");
        assertEquals(r.commitSha(), h.authTip(), "target tip must be exactly the ticket commit");
        assertEquals(baseTip, r.refBefore(), "ref-before must be the base");
    }

    /** A3: modify the worktree after review; publish must reject (TOCTOU) and revert to presubmit. */
    @Test
    void a3_toctouRejectAndRollBack() {
        RepoRef clone = h.createTicket("TICKET-1");
        h.writeFile(clone, "feature.txt", "reviewed content\n");
        h.service().presubmit(new PresubmitCommand("TICKET-1"));
        h.service().review(new ReviewCommand("TICKET-1", null, true, null));

        String tipBefore = h.authTip();
        long countBefore = h.authCommitCount();

        // Tamper with the worktree AFTER review.
        h.writeFile(clone, "feature.txt", "SNEAKY unreviewed content\n");

        GateException ex = assertThrows(GateException.class,
                () -> h.service().publish(new PublishCommand("TICKET-1", null)));
        assertEquals(GateErrorCode.REJECT_TOCTOU, ex.code(), ex.getMessage());
        assertEquals(tipBefore, h.authTip(), "tip must not move on a TOCTOU reject");
        assertEquals(countBefore, h.authCommitCount());
    }

    /** A4: a repeated publish of the same (ticket, round, tree) produces only ONE commit. */
    @Test
    void a4_idempotentPublish() {
        RepoRef clone = h.createTicket("TICKET-1");
        h.writeFile(clone, "feature.txt", "idempotent\n");
        h.service().presubmit(new PresubmitCommand("TICKET-1"));
        h.service().review(new ReviewCommand("TICKET-1", null, true, null));

        long before = h.authCommitCount();
        PublishResult first = h.service().publish(new PublishCommand("TICKET-1", null));
        long afterFirst = h.authCommitCount();
        assertEquals(before + 1, afterFirst, "first publish adds exactly one commit");

        PublishResult second = h.service().publish(new PublishCommand("TICKET-1", null));
        assertEquals(afterFirst, h.authCommitCount(), "second publish must add no commit (A4)");
        assertEquals(first.commitSha(), second.commitSha(), "same commit SHA on replay");
        assertTrue(second.alreadyPublished(), "second publish must report already-published");
    }
}
