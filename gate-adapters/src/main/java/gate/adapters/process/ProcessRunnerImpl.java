package gate.adapters.process;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.ProcessRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The single implementation of {@link ProcessRunner}.
 *
 * <p>stdout and stderr are redirected to temporary <em>files</em> in normal mode, or drained via
 * dedicated threads in streaming mode to prevent pipe buffer stalls while enabling line-by-line consumption.
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

    @Override
    public ProcRun runStreaming(List<String> argv, Path cwd, Map<String, String> env, Duration timeout,
                                Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        Instant started = Instant.now();
        StringBuilder stdoutAcc = new StringBuilder();
        StringBuilder stderrAcc = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv));
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            if (env != null) {
                pb.environment().putAll(env);
            }

            Process process = pb.start();
            process.getOutputStream().close();

            CompletableFuture<Void> outFuture = CompletableFuture.runAsync(() ->
                    drainStream(process.getInputStream(), line -> {
                        synchronized (stdoutAcc) {
                            stdoutAcc.append(line).append("\n");
                        }
                        if (stdoutConsumer != null) {
                            try {
                                stdoutConsumer.accept(line);
                            } catch (Exception ignored) {
                            }
                        }
                    }));

            CompletableFuture<Void> errFuture = CompletableFuture.runAsync(() ->
                    drainStream(process.getErrorStream(), line -> {
                        synchronized (stderrAcc) {
                            stderrAcc.append(line).append("\n");
                        }
                        if (stderrConsumer != null) {
                            try {
                                stderrConsumer.accept(line);
                            } catch (Exception ignored) {
                            }
                        }
                    }));

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

            try {
                CompletableFuture.allOf(outFuture, errFuture).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            return new ProcRun(List.copyOf(argv), exitCode, stdoutAcc.toString(), stderrAcc.toString(),
                    Duration.between(started, Instant.now()), timedOut);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "failed to run " + String.join(" ", argv) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "interrupted running " + String.join(" ", argv), e);
        }
    }

    private static void drainStream(InputStream in, Consumer<String> lineConsumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineConsumer.accept(line);
            }
        } catch (IOException ignored) {
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

