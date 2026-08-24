package gate.adapters.git;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.ports.infra.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin, shared front-end for invoking the real {@code git} binary (ADR-1: no JGit anywhere on the
 * authoritative path).
 *
 * <p>Two things are centralised here on purpose:
 *
 * <p><b>1. Pinned configuration.</b> Every invocation carries
 * {@code -c core.autocrlf=false -c core.safecrlf=false}. This is not tidiness: the same bytes
 * produce a <em>different</em> {@code tree_hash} under a different {@code core.autocrlf} (measured,
 * 架构落地执行文档 §0.1). Capture, the publish-time recomputation and the hook's own
 * {@code rev-parse} must therefore agree on this setting, or the TOCTOU re-check would reject
 * honest work on Windows.
 *
 * <p><b>2. Environment hygiene.</b> {@code GIT_INDEX_FILE} and the identity variables are set
 * explicitly per call and never inherited. Ambient values would leak the caller's index or identity
 * into a capture, breaking both the "agent index untouched" invariant (ADR-5) and commit
 * determinism (I1/I5).
 */
public final class GitCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final ProcessRunner runner;
    private final String gitExecutable;
    private final Duration timeout;

    public GitCli(ProcessRunner runner, String gitExecutable, Duration timeout) {
        this.runner = runner;
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public GitCli(ProcessRunner runner) {
        this(runner, "git", DEFAULT_TIMEOUT);
    }

    /** Runs git in {@code repo} and fails loudly on a non-zero exit. */
    public ProcessRunner.ProcRun must(RepoRef repo, String... args) {
        return must(repo == null ? null : repo.path(), Map.of(), args);
    }

    public ProcessRunner.ProcRun must(Path cwd, Map<String, String> env, String... args) {
        ProcessRunner.ProcRun run = run(cwd, env, args);
        if (!run.ok()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "git " + String.join(" ", args) + " failed (exit=" + run.exitCode()
                            + (run.timedOut() ? ", timed out" : "") + "): " + run.stderrFirstLine());
        }
        return run;
    }

    /** Runs git and returns the outcome, including failures. */
    public ProcessRunner.ProcRun run(RepoRef repo, String... args) {
        return run(repo == null ? null : repo.path(), Map.of(), args);
    }

    public ProcessRunner.ProcRun run(Path cwd, Map<String, String> env, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(gitExecutable);
        argv.add("-c");
        argv.add("core.autocrlf=false");
        argv.add("-c");
        argv.add("core.safecrlf=false");
        argv.addAll(Arrays.asList(args));
        return runner.run(argv, cwd, hygienicEnv(env), timeout);
    }

    /** Single-line stdout, trimmed. Used for {@code rev-parse} / {@code write-tree} style output. */
    public String line(RepoRef repo, String... args) {
        return must(repo, args).stdout().trim();
    }

    /**
     * Environment for a git child process.
     *
     * <p>{@code GIT_CONFIG_NOSYSTEM=1} and an empty {@code GIT_CONFIG_GLOBAL} keep the developer's
     * or CI machine's global config out of every hash we compute — 执行文档 §5.1 requires tests not
     * to depend on local git configuration, and the same reasoning applies in production, where an
     * ambient {@code core.autocrlf=true} would otherwise change {@code tree_hash}.
     *
     * <p>{@code GIT_TERMINAL_PROMPT=0} guarantees a hung credential prompt can never masquerade as
     * a timeout.
     */
    private static Map<String, String> hygienicEnv(Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "");
        env.put("LC_ALL", "C");
        if (extra != null) {
            env.putAll(extra);
        }
        return env;
    }

    public String executable() {
        return gitExecutable;
    }
}
