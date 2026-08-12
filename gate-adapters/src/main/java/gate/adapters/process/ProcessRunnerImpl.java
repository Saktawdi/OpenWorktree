package gate.adapters.process;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The single implementation of {@link ProcessRunner}.
 *
 * <p>stdout and stderr are redirected to temporary <em>files</em>, never read from pipes. Reading
 * from a pipe means a child that fills the OS pipe buffer blocks forever, which in turn means
 * {@code waitFor(timeout)} never fires and the timeout silently never happens — §8.4 item 9. With
 * file redirection the child can never block on us.
 *
 * <p>On timeout the whole process tree is destroyed: {@code descendants()} first, then the process
 * itself, since git spawns helpers (e.g. {@code git-receive-pack}) that would otherwise survive and
 * keep file handles on the repo — on Windows that leaves undeletable {@code .git} directories.
 */
public final class ProcessRunnerImpl implements ProcessRunner {

    private final Path tempRoot;

    public ProcessRunnerImpl(Path tempRoot) {
        this.tempRoot = tempRoot;
        try {
            Files.createDirectories(tempRoot);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create process temp dir " + tempRoot, e);
        }
    }

    @Override
    public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        Path outFile = null;
        Path errFile = null;
        Instant started = Instant.now();
        try {
            outFile = Files.createTempFile(tempRoot, "proc-out-", ".log");
            errFile = Files.createTempFile(tempRoot, "proc-err-", ".log");

            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv));
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            if (env != null) {
                pb.environment().putAll(env);
            }
            pb.redirectOutput(outFile.toFile());
            pb.redirectError(errFile.toFile());

            Process process = pb.start();
            // git plumbing never expects stdin here; closing it prevents a child waiting on input.
            process.getOutputStream().close();

            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            int exitCode;
            boolean timedOut = false;
            if (!exited) {
                timedOut = true;
                killTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                exitCode = process.isAlive() ? -1 : process.exitValue();
            } else {
                exitCode = process.exitValue();
            }
            String stdout = readQuietly(outFile);
            String stderr = readQuietly(errFile);
            return new ProcRun(List.copyOf(argv), exitCode, stdout, stderr,
                    Duration.between(started, Instant.now()), timedOut);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "failed to run " + String.join(" ", argv) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "interrupted running " + String.join(" ", argv), e);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String readQuietly(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover log file is harmless; failing here would mask the real process outcome.
        }
    }
}
