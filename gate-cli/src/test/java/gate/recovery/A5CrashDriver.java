package gate.recovery;

import gate.application.presubmit.PresubmitCommand;
import gate.application.publish.PublishCommand;
import gate.application.review.ReviewCommand;
import gate.domain.git.RepoRef;
import gate.ports.engine.PublishProbe;
import gate.testkit.PersistentGate;
import java.nio.file.Path;

/**
 * Child-JVM driver for the A5 crash-recovery test.
 *
 * <p>Args: {@code <root> <git> <phase>}. It builds the ticket, presubmits, reviews PASS, then calls
 * the real {@code publish} with a {@link PublishProbe} that {@link Runtime#halt(int)}s the JVM at the
 * named phase. {@code halt} skips shutdown hooks and buffer flushes, so it is a faithful "power off"
 * mid-operation — the parent process then reconciles and asserts convergence.
 */
public final class A5CrashDriver {

    public static void main(String[] args) {
        String root = args[0];
        String git = args[1];
        String killPhase = args[2];

        PublishProbe probe = phase -> {
            if (phase.equals(killPhase)) {
                // Hard power-off: no shutdown hooks, no flush. Exit code 137 mimics SIGKILL.
                Runtime.getRuntime().halt(137);
            }
        };

        PersistentGate gate = new PersistentGate(Path.of(root), git, probe);
        gate.initTopology();
        RepoRef clone = gate.createTicket("TICKET-1");
        try {
            Path file = clone.path().resolve("feature.txt");
            java.nio.file.Files.writeString(file, "a5 content\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        gate.service().presubmit(new PresubmitCommand("TICKET-1"));
        gate.service().review(new ReviewCommand("TICKET-1", null, true, null));
        gate.service().publish(new PublishCommand("TICKET-1", null));
        // If we reach here the kill phase never fired; exit non-137 so the test can tell.
        System.exit(0);
    }
}
