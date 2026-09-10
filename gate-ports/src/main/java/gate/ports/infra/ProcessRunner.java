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
     * Run a process with the given {@link StreamSpec}, streaming stdout and stderr line-by-line via
     * consumers.
     *
     * <p>{@link StreamSpec#stdin()} carries free text (agent prompts, user messages); on Windows the
     * CLI locator resolves a bare name to its npm {@code .cmd} shim, and the JDK launches a
     * {@code .cmd} as {@code cmd.exe /c <shim> <args>} — cmd.exe ends the command at the first
     * newline, so a multi-line argument reaches the CLI as its first line only (T-121: 引用 /
     * 图片引用路径行 / 多行指令 all silently dropped). stdin also escapes the 32767-char command
     * line ceiling.
     *
     * <p>{@link StreamSpec#cancel()} is polled while waiting: once it reports true the whole process
     * tree is destroyed and the run returns promptly instead of running to completion (T-120: a
     * user-visible abort must actually stop the agent, not just mark a row).
     */
    ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                         StreamSpec spec, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer);

    /**
     * Run process while streaming stdout and stderr line-by-line via consumers, closing stdin.
     */
    default ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                 Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
        return runStreaming(argv, cwd, env, timeout, StreamSpec.NONE, stdoutConsumer, stderrConsumer);
    }

    /**
     * Cooperative cancellation of a running process. The signal is owned by the caller (a session
     * adapter), so a cancel is not a timeout: implementations must not report {@code timedOut} for a
     * run they killed this way.
     */
    @FunctionalInterface
    interface CancelSignal {

        boolean cancelled();
    }

    /**
     * Streaming transport knobs that are not process identity: what to feed the child's stdin, and
     * how to cancel it mid-run.
     *
     * @param stdin  UTF-8 text for the child's stdin; {@code null} closes stdin immediately
     * @param cancel polled cancellation signal; {@code null} means the run cannot be cancelled
     */
    record StreamSpec(String stdin, CancelSignal cancel) {

        /** No stdin, not cancellable. */
        public static final StreamSpec NONE = new StreamSpec(null, null);
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
