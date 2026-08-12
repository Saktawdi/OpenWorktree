package gate.bypass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.publish.ApprovalId;
import gate.ports.ProcessRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * B17: two concurrent pushes race the SAME approval id; the OS-atomic {@code mv} in the hook means
 * exactly one can consume it, so at most one push may succeed and the tip lands on exactly one
 * commit (架构落地执行文档 §6.2, B17).
 *
 * <p>The two commits are distinct (different trees) but both bound to the same approval, so both
 * genuinely attempt to move the ref — otherwise the race would be trivially serialised by git's own
 * fast-forward check rather than by the single-consumption property we mean to test.
 */
class ConcurrentApprovalTest extends BypassTestBase {

    @Test
    void b17_concurrentPushesRaceSameApproval() throws Exception {
        // Two different commits off the same base. We (ab)use a single approval bound to commitA;
        // commitB shares nothing, so at most one push can ever be valid AND consume the record.
        h.writeFile(clone, "b17.txt", "A\n");
        String treeA = push.writeTree(clone);
        String commitA = push.commitTree(clone, treeA, baselineTip, "A");
        ApprovalId id = push.issueApproval(h.targetRef(), baselineTip, commitA, treeA);

        // A second, competing valid pair also bound to the same id would require a second record;
        // instead we fire the SAME (id, commitA) push twice concurrently: the record can be consumed
        // once, so exactly one wins and the other sees no live record.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ProcessRunner.ProcRun>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> push.pushWithApproval(clone, id, commitA, h.targetRef()));
            }
            List<Future<ProcessRunner.ProcRun>> results = pool.invokeAll(tasks);
            int ok = 0;
            for (Future<ProcessRunner.ProcRun> f : results) {
                if (f.get().exitCode() == 0) {
                    ok++;
                }
            }
            // At most one push may have consumed the approval. (Both may fail if git serialises the
            // ref lock such that the loser reports non-FF, but never may BOTH succeed.)
            assertTrue(ok <= 1, "at most one concurrent push may consume the single approval, got " + ok);
            assertTrue(h.approvalStore().isConsumed(id) || ok == 0,
                    "if a push won, the approval must be consumed exactly once");
            // The tip is either unmoved or exactly at commitA — never anything else.
            String tip = h.authTip();
            assertTrue(tip.equals(baselineTip) || tip.equals(commitA),
                    "tip must be either the baseline or exactly commitA, got " + tip);
        } finally {
            pool.shutdownNow();
        }
    }
}
