package gate.bypass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.git.RepoRef;
import gate.domain.publish.ApprovalId;
import gate.ports.infra.ProcessRunner;
import gate.testkit.GateHarness;
import gate.testkit.PushKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for the bypass matrix (架构落地执行文档 §11.2, B1–B19).
 *
 * <p>The single, uniform assertion for every attack is: <b>the authoritative target branch tip does
 * not move</b>. That is the property the whole project exists to guarantee (spike-结论 §2.2). Each
 * attack constructs a genuinely hostile push with the real git binary — no mocking, no shortcut —
 * and asserts REJECT plus an unchanged tip against a baseline captured in {@link #setUp()}.
 *
 * <p>These tests live in the same commit as the gate code by mandate (§8.3): the bypass suite is the
 * product test, and shipping the gate without it is shipping an unverified gate.
 */
abstract class BypassTestBase {

    protected GateHarness h;
    protected PushKit push;
    protected RepoRef clone;
    protected String baselineTip;

    @BeforeEach
    void setUp() {
        h = new GateHarness();
        push = new PushKit(h);
        clone = h.createTicket("TICKET-1");
        baselineTip = h.authTip();
        assertFalse(baselineTip.isEmpty(), "auth.git must have a seeded base commit before an attack");
    }

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    /** The uniform bypass assertion: push failed AND the authoritative tip is unchanged. */
    protected void assertRejectedAndTipUnchanged(ProcessRunner.ProcRun pushRun) {
        assertNotEquals(0, pushRun.exitCode(),
                "push must be REJECTED (non-zero exit); stderr=" + pushRun.stderrFirstLine());
        assertEquals(baselineTip, h.authTip(),
                "authoritative target tip must not move under attack");
    }

    /** Convenience: a fresh legitimate commit on top of the base, staged from a written file. */
    protected String makeCommit(String file, String content, String message) {
        h.writeFile(clone, file, content);
        String tree = push.writeTree(clone);
        return push.commitTree(clone, tree, baselineTip, message);
    }
}
