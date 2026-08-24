package gate.bypass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.git.RepoRef;
import gate.domain.publish.ApprovalId;
import gate.ports.infra.ProcessRunner;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bypass matrix B1–B19 (架构落地执行文档 §11.2). One test per attack, uniform assertion:
 * REJECT and the authoritative tip never moves.
 */
class BypassMatrixTest extends BypassTestBase {

    /** B1: commit with --no-verify (bypass client hooks) then push with no gate token. */
    @Test
    void b1_noVerifyPushWithoutToken() {
        h.writeFile(clone, "b1.txt", "b1\n");
        h.git(clone, "add", "-A");
        h.gitIn(clone.path(), h.identityEnv(), "commit", "--no-verify", "-m", "b1");
        String head = h.git(clone, "rev-parse", "HEAD").stdout().trim();
        ProcessRunner.ProcRun run = push.pushNoOption(clone, head, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B2: -c core.hooksPath=/dev/null to disable local hooks, then push (no token). */
    @Test
    void b2_hooksPathDevNull() {
        h.writeFile(clone, "b2.txt", "b2\n");
        h.git(clone, "add", "-A");
        h.gitIn(clone.path(), h.identityEnv(), "-c", "core.hooksPath=/dev/null", "commit", "-m", "b2");
        String head = h.git(clone, "rev-parse", "HEAD").stdout().trim();
        ProcessRunner.ProcRun run = push.pushNoOption(clone, head, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B3: delete the clone's local hooks, commit, push. The gate is server-side, so this is inert. */
    @Test
    void b3_deleteLocalHookThenPush() throws Exception {
        java.nio.file.Path localHooks = clone.path().resolve(".git").resolve("hooks");
        if (Files.isDirectory(localHooks)) {
            try (var s = Files.list(localHooks)) {
                for (var p : s.toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        String commit = makeCommit("b3.txt", "b3\n", "b3");
        ProcessRunner.ProcRun run = push.pushNoOption(clone, commit, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B4: pure plumbing (hash-object/write-tree/commit-tree) then push, no token. */
    @Test
    void b4_pumbingThenPush() {
        String commit = makeCommit("b4.txt", "b4\n", "b4");  // makeCommit is exactly plumbing
        ProcessRunner.ProcRun run = push.pushNoOption(clone, commit, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B5: replay a consumed token to push a new commit. */
    @Test
    void b5_replayConsumedToken() {
        // First, a legitimate push that consumes an approval.
        String c1 = makeCommit("b5a.txt", "b5a\n", "b5a");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, c1, t1);
        ProcessRunner.ProcRun ok = push.pushWithApproval(clone, id, c1, h.targetRef());
        assertEquals(0, ok.exitCode(), "the first legitimate push must succeed");
        String movedTip = h.authTip();
        assertEquals(c1, movedTip);

        // Now replay the same (now consumed) id to push a different commit.
        String c2 = push.commitTree(clone, t1, c1, "b5b");
        ProcessRunner.ProcRun replay = push.pushWithApproval(clone, id, c2, h.targetRef());
        assertNotEquals(0, replay.exitCode(), "replay of a consumed approval must be REJECTED");
        assertEquals(movedTip, h.authTip(), "tip must stay at the first legitimate commit");
    }

    /** B6: valid approval, but push a commit whose tree differs from the bound tree. */
    @Test
    void b6_tokenTreeMismatch() {
        String cGood = makeCommit("b6good.txt", "good\n", "good");
        String tGood = h.git(clone, "rev-parse", cGood + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, cGood, tGood);

        // Build a DIFFERENT commit (different tree) and try to push it under the same approval.
        h.writeFile(clone, "b6evil.txt", "evil\n");
        String tEvil = push.writeTree(clone);
        String cEvil = push.commitTree(clone, tEvil, baselineTip, "evil");
        assertNotEquals(tGood, tEvil);
        ProcessRunner.ProcRun run = push.pushWithApproval(clone, id, cEvil, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B7: push to a non-whitelisted ref. */
    @Test
    void b7_nonWhitelistedRef() {
        String commit = makeCommit("b7.txt", "b7\n", "b7");
        String tree = h.git(clone, "rev-parse", commit + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval("refs/heads/tmp", baselineTip, commit, tree);
        ProcessRunner.ProcRun run = push.pushWithApproval(clone, id, commit, "refs/heads/tmp");
        assertNotEquals(0, run.exitCode(), "push to a non-whitelisted ref must be REJECTED");
        assertEquals(baselineTip, h.authTip());
        assertTrue(h.git(h.authRepo(), "rev-parse", "--verify", "refs/heads/tmp").exitCode() != 0,
                "the non-whitelisted ref must not have been created");
    }

    /** B8: force push a non-fast-forward over reviewed history. */
    @Test
    void b8_forcePushNonFastForward() {
        // Land a legitimate commit first.
        String c1 = makeCommit("b8a.txt", "b8a\n", "b8a");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        ApprovalId id1 = push.issueApproval(h.targetRef(), baselineTip, c1, t1);
        assertEquals(0, push.pushWithApproval(clone, id1, c1, h.targetRef()).exitCode());
        String tip1 = h.authTip();

        // Build a divergent commit off the ORIGINAL base (non-FF) and force-push it.
        h.writeFile(clone, "b8b.txt", "b8b\n");
        String t2 = push.writeTree(clone);
        String c2 = push.commitTree(clone, t2, baselineTip, "b8b-divergent");
        ApprovalId id2 = push.issueApproval(h.targetRef(), baselineTip, c2, t2);
        ProcessRunner.ProcRun run = push.forcePush(clone, id2, c2, h.targetRef());
        assertNotEquals(0, run.exitCode(), "non-fast-forward force push must be REJECTED");
        assertEquals(tip1, h.authTip(), "tip must stay at the legitimately landed commit");
    }

    /** B9: agent honestly computes the tree of its own UNreviewed commit and uses it as a token. */
    @Test
    void b9_honestTreeOfUnreviewedCommit() {
        // No approval record is issued at all — the agent just computes tree honestly.
        h.writeFile(clone, "b9.txt", "unreviewed\n");
        String tree = push.writeTree(clone);
        String commit = push.commitTree(clone, tree, baselineTip, "b9");
        // The refuted design accepted tree=<tree>. Our push-option carries a random-looking id that
        // simply does not exist as a record.
        ProcessRunner.ProcRun run = push.pushWithRawOption(clone,
                "gate-approval=" + "0".repeat(32), commit, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B10: one push carrying two ref updates. */
    @Test
    void b10_twoRefUpdatesInOnePush() {
        String c1 = makeCommit("b10.txt", "b10\n", "b10");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, c1, t1);
        ProcessRunner.ProcRun run = h.gitIn(clone.path(), Map.of(),
                "push", "--push-option=gate-approval=" + id.value(),
                h.authRepo().pathString(),
                c1 + ":" + h.targetRef(),
                c1 + ":refs/heads/main2");
        assertNotEquals(0, run.exitCode(), "a push with two ref updates must be REJECTED");
        assertEquals(baselineTip, h.authTip());
    }

    /** B11: ref deletion (new = 0000...). */
    @Test
    void b11_refDeletion() {
        String c1 = makeCommit("b11.txt", "b11\n", "b11");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, c1, t1);
        ProcessRunner.ProcRun run = h.gitIn(clone.path(), Map.of(),
                "push", "--push-option=gate-approval=" + id.value(),
                "--delete", h.authRepo().pathString(), h.targetRef());
        assertNotEquals(0, run.exitCode(), "ref deletion must be REJECTED");
        assertEquals(baselineTip, h.authTip());
    }

    /** B12: new object is an annotated tag, not a commit. */
    @Test
    void b12_annotatedTagNotCommit() {
        String c1 = makeCommit("b12.txt", "b12\n", "b12");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        // Create an annotated tag object pointing at the commit.
        h.gitIn(clone.path(), h.identityEnv(), "tag", "-a", "b12tag", "-m", "tag", c1);
        String tagObj = h.git(clone, "rev-parse", "b12tag").stdout().trim();
        assertNotEquals(c1, tagObj, "the tag object must differ from the commit");
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, tagObj, t1);
        ProcessRunner.ProcRun run = push.pushWithApproval(clone, id, tagObj, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /**
     * B14: a linked worktree shares the ref store; presubmit must refuse to operate on it, and the
     * preflight clone-independence check must flag it. (The raw update-ref bypass itself is a
     * property of git we do not re-prove here; we prove the gate REFUSES such a clone.)
     */
    @Test
    void b14_linkedWorktreeRefused() {
        // Create a linked worktree off the clone; its .git is a FILE that shares the ref store.
        java.nio.file.Path lw = h.root().resolve("linked-wt");
        h.gitIn(clone.path(), Map.of(), "worktree", "add", "-b", "feature", lw.toString());
        RepoRef linked = RepoRef.of(lw);
        assertTrue(Files.isRegularFile(lw.resolve(".git")), "linked worktree .git must be a file");

        // The gate must refuse to operate on a linked worktree (SnapshotCapture guard, §2.2).
        gate.ports.git.SnapshotCapture capture = new gate.adapters.git.GitCliSnapshot(h.gitCli(), h.config().indexDir());
        org.junit.jupiter.api.Assertions.assertThrows(gate.domain.error.GateException.class,
                () -> capture.capture(linked, h.authRepo(), h.targetRef()),
                "the gate must refuse to operate on a linked worktree (B14)");
        assertEquals(baselineTip, h.authTip());
    }

    /** B15: graft an already-reviewed tree onto a DIFFERENT parent (snapshot laundering). */
    @Test
    void b15_snapshotLaunderingDifferentParent() {
        // Land a first legitimate commit c1 so there is a "different parent" to graft onto.
        String c1 = makeCommit("b15a.txt", "b15a\n", "b15a");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        ApprovalId id1 = push.issueApproval(h.targetRef(), baselineTip, c1, t1);
        assertEquals(0, push.pushWithApproval(clone, id1, c1, h.targetRef()).exitCode());
        String tipAfterC1 = h.authTip();

        // Build a reviewed tree t2 as a commit whose parent is baselineTip (old), get an approval for
        // (old=baselineTip, new=cReviewed, tree=t2). Then graft t2 onto a DIFFERENT parent c1.
        h.writeFile(clone, "b15b.txt", "b15b\n");
        String t2 = push.writeTree(clone);
        String cReviewed = push.commitTree(clone, t2, baselineTip, "reviewed");
        ApprovalId id2 = push.issueApproval(h.targetRef(), baselineTip, cReviewed, t2);
        // Grafted: same tree t2, but parent is c1 (not baselineTip == the approval's old).
        String cGrafted = push.commitTree(clone, t2, c1, "grafted");
        ProcessRunner.ProcRun run = push.pushWithApproval(clone, id2, cGrafted, h.targetRef());
        assertNotEquals(0, run.exitCode(), "grafting a reviewed tree onto a different parent must be REJECTED (parent==old)");
        assertEquals(tipAfterC1, h.authTip());
    }

    /** B16: replay an already-consumed approval id to push a NEW commit. */
    @Test
    void b16_replayConsumedApproval() {
        String c1 = makeCommit("b16.txt", "b16\n", "b16");
        String t1 = h.git(clone, "rev-parse", c1 + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, c1, t1);
        assertEquals(0, push.pushWithApproval(clone, id, c1, h.targetRef()).exitCode());
        String tip1 = h.authTip();
        assertTrue(h.approvalStore().isConsumed(id), "approval must be consumed after the push");

        // Reuse the consumed id for a NEW commit on top of c1. Re-pushing c1 itself would be a git
        // no-op (exit 0, nothing moves); the real attack is reusing the spent credential for new work.
        String c2 = push.commitTree(clone, t1, c1, "b16-new");
        ProcessRunner.ProcRun run = push.pushWithApproval(clone, id, c2, h.targetRef());
        assertNotEquals(0, run.exitCode(), "reusing a consumed approval for a new commit must be REJECTED");
        assertEquals(tip1, h.authTip());
    }

    /** B18: guess/forge an approval id that was never issued. */
    @Test
    void b18_guessApprovalId() {
        String commit = makeCommit("b18.txt", "b18\n", "b18");
        ProcessRunner.ProcRun run = push.pushWithRawOption(clone,
                "gate-approval=" + "deadbeef".repeat(4), commit, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** B19: approval id with path traversal in it. */
    @Test
    void b19_approvalIdPathTraversal() {
        String commit = makeCommit("b19.txt", "b19\n", "b19");
        ProcessRunner.ProcRun run = push.pushWithRawOption(clone,
                "gate-approval=../../etc/passwd", commit, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }

    /** Extra (from spike): two push-options -> COUNT != 1. */
    @Test
    void multiOption_countNotOne() {
        String commit = makeCommit("bm.txt", "bm\n", "bm");
        String tree = h.git(clone, "rev-parse", commit + "^{tree}").stdout().trim();
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, commit, tree);
        ProcessRunner.ProcRun run = push.pushWithTwoOptions(clone,
                "gate-approval=" + id.value(), "gate-approval=" + id.value(), commit, h.targetRef());
        assertRejectedAndTipUnchanged(run);
    }
}
