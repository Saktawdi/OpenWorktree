package gate.bypass;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.ports.infra.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * B13: {@code git push --receive-pack=<own script>} (架构落地执行文档 §11.2, §1.3).
 *
 * <p>This is explicitly a <b>residual risk</b>, not a protocol-layer bypass the gate claims to stop.
 * A same-OS-privilege actor who can name an alternate receive-pack is, by definition, operating
 * outside the git protocol path the gate guards — the threat model (§1.3) concedes this class
 * openly. The honest thing to assert is therefore not "REJECT" but "documented and detectable":
 *
 * <ul>
 *   <li>pushing to the real {@code auth.git} still goes through the installed hook when the standard
 *       {@code git-receive-pack} is used, so an attacker must actively substitute the binary;</li>
 *   <li>any such substitution is a file-level action leaving reflog / hook-hash evidence, which the
 *       audit + preflight machinery is designed to detect (not prevent).</li>
 * </ul>
 *
 * <p>The test records the residual-risk boundary so a future reader does not mistake its absence
 * from the REJECT matrix for an oversight.
 */
@Tag("slow")
class ResidualRiskB13Test extends BypassTestBase {

    @Test
    void b13_customReceivePackIsResidualRiskNotProtocolBypass() {
        String commit = makeCommit("b13.txt", "b13\n", "b13");
        // A --receive-pack override pointing at a non-existent script simply fails to run; this is
        // NOT the gate rejecting a protocol push, it is the attacker leaving the protocol entirely.
        ProcessRunner.ProcRun run = h.gitIn(clone.path(), java.util.Map.of(),
                "push", "--receive-pack=this-binary-does-not-exist",
                h.authRepo().pathString(), commit + ":" + h.targetRef());
        // Whatever the exit, the authoritative tip must not have advanced via the normal gate path.
        assertTrue(h.authTip().equals(baselineTip),
                "a custom receive-pack must not have advanced the tip through the gate; "
                        + "if a real attacker supplies a working script that is the conceded §1.3 residual risk, "
                        + "detected via reflog + hook-hash drift, not prevented at the protocol layer");
    }
}
