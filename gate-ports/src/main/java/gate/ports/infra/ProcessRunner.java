package gate.ports.infra;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The only seam that owns process mechanics: timeout, {@code destroyForcibly}, reaping
 * descendants, draining stdout/stderr to files, argv escaping.
 *
 * <p>Centralising it closes §8.4 item 9: if stdout/stderr are read from pipes, a full pipe buffer
 * blocks the reader so {@code waitFor(timeout)} never fires and the "timeout" never happens.
 * Implementations must redirect to files instead.
 */
public interface ProcessRunner {

    ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout);

    /**
     * Run process while streaming stdout and stderr line-by-line via consumers.
     */
    default ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                 Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
        return run(argv, cwd, env, timeout);
    }

    /**
     * @param timedOut true when the process was killed by the timeout, in which case
     *                 {@code exitCode} is whatever the OS reported after {@code destroyForcibly}
     *                 and must not be interpreted as the program's own exit status
     */
    record ProcRun(List<String> argv, int exitCode, String stdout, String stderr, Duration duration, boolean timedOut) {

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** First line of stderr, for error messages. Never used for control flow decisions. */
        public String stderrFirstLine() {
            return stderr.lines().findFirst().orElse("");
        }
    }
}

