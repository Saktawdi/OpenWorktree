package gate.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.application.ReconcileResult;
import gate.domain.git.RepoRef;
import gate.ports.PublishProbe;
import gate.testkit.PersistentGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A5 crash recovery (架构落地执行文档 §4 P1, §7.4).
 *
 * <p>Each case spawns a child JVM that drives a real publish and is hard-killed with
 * {@link Runtime#halt} at a precise crash point — "after commit-tree" (C3) and "mid-push" (C4/C5).
 * The parent then re-opens the same gate-home and runs the real {@code reconcile}, asserting the
 * state converges and no duplicate commit exists.
 *
 * <p>Convergence is derived from {@code auth.git}, never the DB (I4): a kill after commit-tree must
 * leave the tip unmoved and reconcile must be able to complete the publish; a kill mid-push must
 * converge to exactly one commit whether or not receive-pack had accepted before the halt.
 */
class A5CrashRecoveryTest {

    @Test
    void killAfterCommitTree_thenReconcile_convergesNoDuplicate() throws Exception {
        Path root = Files.createTempDirectory("gate-a5-c3-");
        try {
            int exit = runChild(root, "AFTER_COMMIT_TREE");
            assertEquals(137, exit, "child must have been hard-killed at the commit-tree phase");

            // Re-open the same gate-home. The commit was built and pinned, but nothing was pushed.
            PersistentGate gate = new PersistentGate(root, "git", PublishProbe.NOOP);
            long countBefore = gate.authCommitCount();

            ReconcileResult r = gate.service().reconcile(new gate.application.ReconcileCommand(null));
            assertTrue(r.outcomes().size() >= 1, "reconcile must observe the pending intent");

            // After a C3 kill the tip has not moved; reconcile leaves it retryable (base intact), and
            // a subsequent publish lands exactly one commit.
            long afterReconcile = gate.authCommitCount();
            gate.service().publish(new gate.application.PublishCommand("TICKET-1", null));
            assertEquals(afterReconcile + 1, gate.authCommitCount(),
                    "completing the interrupted publish must add exactly one commit (no duplicate)");
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(root);
        }
    }

    @Test
    void killMidPush_thenReconcile_convergesToSingleCommit() throws Exception {
        Path root = Files.createTempDirectory("gate-a5-c4-");
        try {
            int exit = runChild(root, "AFTER_PUSH");
            assertEquals(137, exit, "child must have been hard-killed right after the push");

            PersistentGate gate = new PersistentGate(root, "git", PublishProbe.NOOP);

            // The push may or may not have landed before the halt. Either way, reconcile must
            // converge to a single, non-duplicated commit, deriving truth from auth.git.
            ReconcileResult r = gate.service().reconcile(new gate.application.ReconcileCommand(null));
            assertTrue(r.outcomes().size() >= 1);

            long count = gate.authCommitCount();
            // Complete/replay the publish; idempotence guarantees at most one ticket commit total.
            gate.service().publish(new gate.application.PublishCommand("TICKET-1", null));
            long finalCount = gate.authCommitCount();
            // base + exactly one ticket commit.
            assertEquals(2, finalCount, "there must be exactly base + one ticket commit after recovery");
            assertTrue(finalCount >= count, "commit count must not decrease");
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(root);
        }
    }

    private int runChild(Path root, String phase) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "gate.recovery.A5CrashDriver",
                root.toString(), "git", phase);
        pb.redirectErrorStream(true);
        pb.redirectOutput(root.resolve("child.log").toFile());
        Process p = pb.start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("child JVM did not finish in time");
        }
        return p.exitValue();
    }
}
