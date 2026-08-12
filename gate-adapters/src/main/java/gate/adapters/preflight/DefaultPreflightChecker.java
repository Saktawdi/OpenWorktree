package gate.adapters.preflight;

import gate.adapters.git.GitCli;
import gate.adapters.approval.FsApprovalStore;
import gate.domain.config.GateConfig;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.publish.ApprovalGrant;
import gate.domain.publish.ApprovalId;
import gate.ports.HookInstaller;
import gate.ports.PreflightChecker;
import gate.ports.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-tier startup preflight (架构落地执行文档 §10.3).
 *
 * <p><b>CORE</b> checks are always enforced. <b>ENGINE</b> checks only run once an external review
 * engine is configured — in P1 no engine is wired in, and refusing to start for that reason would
 * make the gate unusable while adding nothing.
 *
 * <p>The most important check is {@code hook.selfcheck}, and it exists because of an asymmetry that
 * is easy to miss: <b>a broken hook and a perfect hook look identical from outside</b> — both reject
 * every push. A CRLF line ending, a BOM, or a template typo therefore degrades the gate into a
 * permanent-reject that nobody notices, which is the dual of the silent-allow holes catalogued in
 * §8.4. Only actually executing the hook and observing an ACCEPT distinguishes the two.
 *
 * <p>The self-check runs against a throwaway <b>sandbox</b> bare repo, built by the same generator and
 * the same writer as the real one, and never pushes to the real target branch. Pushing a self-check
 * commit into authoritative history would add a commit that {@code denyDeletes} +
 * {@code denyNonFastForwards} make unremovable, and would break the A1 accounting. After the sandbox
 * proves the script runs, the real hook is compared byte-for-byte by sha256.
 */
public final class DefaultPreflightChecker implements PreflightChecker {

    private final GateConfig config;
    private final GitCli git;
    private final HookInstaller hookInstaller;
    private final ProcessRunner processRunner;

    public DefaultPreflightChecker(GateConfig config, GitCli git, HookInstaller hookInstaller) {
        this(config, git, hookInstaller, null);
    }

    public DefaultPreflightChecker(GateConfig config, GitCli git, HookInstaller hookInstaller,
                                   ProcessRunner processRunner) {
        this.config = config;
        this.git = git;
        this.hookInstaller = hookInstaller;
        this.processRunner = processRunner;
    }

    @Override
    public Report check() {
        List<Check> checks = new ArrayList<>();
        RepoRef auth = RepoRef.of(config.authRepo());

        checks.add(checkBare(auth));
        checks.add(checkHeadSymbolicRef(auth));
        checks.add(checkHookPresent(auth));
        checks.add(checkHookDigest(auth));
        checks.add(checkHooksPathUnset(auth));
        checks.add(checkReceiveConfig(auth, "receive.advertisePushOptions"));
        checks.add(checkReceiveConfig(auth, "receive.denyDeletes"));
        checks.add(checkReceiveConfig(auth, "receive.denyNonFastForwards"));
        checks.add(checkApprovalsDir());
        checks.add(checkClonesIndependent());
        checks.add(checkLocalVolume());
        checks.add(hookSelfCheck());

        if (config.engineConfigured()) {
            checks.add(checkEngineBinary());
        } else {
            checks.add(Check.pass("engine.skipped",
                    "no review engine configured (P1): engine checks deferred to P2", Tier.ENGINE));
        }
        return new Report(checks);
    }

    private Check checkBare(RepoRef auth) {
        ProcessRunner.ProcRun run = git.run(auth, "rev-parse", "--is-bare-repository");
        if (!run.ok()) {
            return Check.fail("auth.bare", "cannot query " + auth.pathString() + ": " + run.stderrFirstLine(), Tier.CORE);
        }
        boolean bare = "true".equals(run.stdout().trim());
        return bare
                ? Check.pass("auth.bare", auth.pathString() + " is bare", Tier.CORE)
                : Check.fail("auth.bare", auth.pathString() + " is not a bare repository", Tier.CORE);
    }

    private Check checkHeadSymbolicRef(RepoRef auth) {
        ProcessRunner.ProcRun run = git.run(auth, "symbolic-ref", "HEAD");
        if (!run.ok()) {
            return Check.fail("auth.head", "HEAD is not a symbolic ref: " + run.stderrFirstLine(), Tier.CORE);
        }
        String head = run.stdout().trim();
        if (!config.isWhitelisted(head)) {
            return Check.fail("auth.head",
                    "HEAD points at " + head + ", which is not in the whitelist " + config.targetRefWhitelist()
                            + " (N1: git 2.37 defaults to master; clones would land on an empty branch)", Tier.CORE);
        }
        return Check.pass("auth.head", "HEAD -> " + head, Tier.CORE);
    }

    private Check checkHookPresent(RepoRef auth) {
        Path hook = auth.path().resolve("hooks").resolve("pre-receive");
        if (!Files.isRegularFile(hook)) {
            return Check.fail("hook.present", "missing " + hook, Tier.CORE);
        }
        try {
            byte[] bytes = Files.readAllBytes(hook);
            for (byte b : bytes) {
                if (b == '\r') {
                    return Check.fail("hook.present",
                            hook + " contains CR: sh will fail to parse it and the gate degrades to permanent-reject",
                            Tier.CORE);
                }
            }
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                return Check.fail("hook.present", hook + " starts with a UTF-8 BOM", Tier.CORE);
            }
            return Check.pass("hook.present", hook + " present, LF-only, no BOM", Tier.CORE);
        } catch (IOException e) {
            return Check.fail("hook.present", "cannot read " + hook + ": " + e.getMessage(), Tier.CORE);
        }
    }

    private Check checkHookDigest(RepoRef auth) {
        String installed = hookInstaller.installedDigest(auth);
        String expected = hookInstaller.expectedDigest(config.targetRefWhitelist(), config.approvalsDir());
        return installed.equals(expected)
                ? Check.pass("hook.digest", "sha256=" + installed, Tier.CORE)
                : Check.fail("hook.digest",
                        "hook content drifted: installed=" + installed + " expected=" + expected
                                + " (the hook is a supply-chain surface; a same-privilege agent can rewrite it, so "
                                + "this check detects rather than prevents)", Tier.CORE);
    }

    private Check checkHooksPathUnset(RepoRef auth) {
        ProcessRunner.ProcRun run = git.run(auth, "config", "--local", "--get", "core.hooksPath");
        if (run.exitCode() == 0 && !run.stdout().trim().isEmpty()) {
            return Check.fail("auth.hooksPath",
                    "core.hooksPath is set to '" + run.stdout().trim() + "': the generated pre-receive would be bypassed",
                    Tier.CORE);
        }
        return Check.pass("auth.hooksPath", "unset", Tier.CORE);
    }

    private Check checkReceiveConfig(RepoRef auth, String key) {
        ProcessRunner.ProcRun run = git.run(auth, "config", "--local", "--get", key);
        String value = run.ok() ? run.stdout().trim() : "";
        if (!"true".equals(value)) {
            String extra = key.equals("receive.advertisePushOptions")
                    ? " — without it the client cannot send a push-option at all "
                      + "(fatal: the receiving end does not support push options, exit 128), so the release path is dead"
                    : "";
            return Check.fail("auth." + key, key + "=" + (value.isEmpty() ? "<unset>" : value) + ", expected true" + extra,
                    Tier.CORE);
        }
        return Check.pass("auth." + key, key + "=true", Tier.CORE);
    }

    private Check checkApprovalsDir() {
        Path approvals = config.approvalsDir();
        Path consumed = approvals.resolve("consumed");
        if (!Files.isDirectory(approvals)) {
            return Check.fail("approvals.dir", "missing " + approvals, Tier.CORE);
        }
        if (!Files.isDirectory(consumed)) {
            return Check.fail("approvals.dir", "missing " + consumed, Tier.CORE);
        }
        if (!Files.isWritable(approvals) || !Files.isWritable(consumed)) {
            return Check.fail("approvals.dir", approvals + " or consumed/ is not writable", Tier.CORE);
        }
        return Check.pass("approvals.dir", approvals + " ready", Tier.CORE);
    }

    /**
     * Rejects any clone that shares a ref store or object store with {@code auth.git}: one
     * {@code update-ref} inside a linked worktree rewrites the authoritative branch with no push, no
     * receive-pack and no hook (B14, reproduced in P0).
     */
    private Check checkClonesIndependent() {
        Path clonesRoot = config.clonesRoot();
        if (!Files.isDirectory(clonesRoot)) {
            return Check.pass("clones.independent", "no clones yet at " + clonesRoot, Tier.CORE);
        }
        List<String> offenders = new ArrayList<>();
        try (var stream = Files.list(clonesRoot)) {
            for (Path candidate : stream.toList()) {
                if (!Files.isDirectory(candidate)) {
                    continue;
                }
                Path dotGit = candidate.resolve(".git");
                if (Files.isRegularFile(dotGit)) {
                    offenders.add(candidate.getFileName() + " (.git is a file => linked worktree)");
                    continue;
                }
                Path alternates = dotGit.resolve("objects").resolve("info").resolve("alternates");
                if (Files.exists(alternates)) {
                    offenders.add(candidate.getFileName() + " (objects/info/alternates present)");
                }
            }
        } catch (IOException e) {
            return Check.fail("clones.independent", "cannot scan " + clonesRoot + ": " + e.getMessage(), Tier.CORE);
        }
        return offenders.isEmpty()
                ? Check.pass("clones.independent", "all clones are independent", Tier.CORE)
                : Check.fail("clones.independent", "shared-ref-store clones found: " + String.join(", ", offenders),
                        Tier.CORE);
    }

    /**
     * {@code FileLock} and atomic rename are unreliable on UNC/network paths, and cloud sync clients
     * corrupt {@code .git} directories (§9.2). A warning-level finding would be ignored, so this is a
     * hard failure on obviously synced paths.
     */
    private Check checkLocalVolume() {
        String path = config.gateHome().toAbsolutePath().toString();
        if (path.startsWith("\\\\") || path.startsWith("//")) {
            return Check.fail("path.localVolume", "gate_home is on a UNC/network path: " + path, Tier.CORE);
        }
        for (String marker : List.of("OneDrive", "Dropbox", "Google Drive", "iCloudDrive")) {
            if (path.contains(marker)) {
                return Check.fail("path.localVolume",
                        "gate_home lives under a cloud-synced directory (" + marker + "): " + path, Tier.CORE);
            }
        }
        return Check.pass("path.localVolume", path, Tier.CORE);
    }

    /**
     * Forward self-check: build a sandbox bare repo with the same generator/writer, push a legitimate
     * approval (expect ACCEPT), then push with no push-option (expect REJECT).
     */
    private Check hookSelfCheck() {
        Path sandbox = config.gateHome().resolve("selfcheck-" + System.nanoTime());
        try {
            Path auth = sandbox.resolve("auth.git");
            Path approvals = sandbox.resolve("approvals");
            Path work = sandbox.resolve("work");
            Files.createDirectories(sandbox);

            String targetRef = config.primaryTargetRef();
            String branch = targetRef.substring("refs/heads/".length());

            git.must(sandbox, Map.of(), "init", "--bare", auth.toString());
            RepoRef authRef = RepoRef.of(auth);
            git.must(authRef, "symbolic-ref", "HEAD", targetRef);
            git.must(authRef, "config", "receive.advertisePushOptions", "true");
            git.must(authRef, "config", "receive.denyDeletes", "true");
            git.must(authRef, "config", "receive.denyNonFastForwards", "true");

            FsApprovalStore sandboxApprovals = new FsApprovalStore(approvals);

            // Seed a base commit BEFORE installing the hook, for the same reason gate init does:
            // the hook demands exactly one parent, so a root commit cannot pass it.
            Files.createDirectories(work);
            git.must(sandbox, Map.of(), "init", "-b", branch, work.toString());
            RepoRef workRef = RepoRef.of(work);
            git.must(workRef, "config", "user.name", "gate");
            git.must(workRef, "config", "user.email", "gate@localhost");
            git.must(workRef, "config", "core.autocrlf", "false");
            Files.writeString(work.resolve("seed.txt"), "seed\n", StandardCharsets.UTF_8);
            git.must(workRef, "add", "-A");
            git.must(work, identityEnv(), "commit", "-m", "seed");
            git.must(work, Map.of(), "push", auth.toString(), "HEAD:" + targetRef);
            ObjectId base = ObjectId.of(git.line(authRef, "rev-parse", "--verify", targetRef));

            // Same generator, same writer as production.
            hookInstaller.install(authRef, config.targetRefWhitelist(), approvals);

            // --- happy path: expect ACCEPT ---
            Files.writeString(work.resolve("change.txt"), "change\n", StandardCharsets.UTF_8);
            git.must(workRef, "add", "-A");
            String tree = git.line(workRef, "write-tree");
            String commit = git.must(work, identityEnv(),
                    "commit-tree", tree, "-p", base.hex(), "-m", "selfcheck").stdout().trim();
            ApprovalId id = sandboxApprovals.allocate();
            sandboxApprovals.issue(id, new ApprovalGrant(targetRef, base, ObjectId.of(commit), ObjectId.of(tree)));
            ProcessRunner.ProcRun accept = git.run(work, Map.of(),
                    "push", "--push-option=" + id.pushOption(), auth.toString(), commit + ":" + targetRef);
            if (!accept.ok()) {
                return Check.fail("hook.selfcheck",
                        "sandbox happy-path push was REJECTED (exit=" + accept.exitCode() + "): "
                                + accept.stderrFirstLine()
                                + " — the hook is broken; a broken hook looks exactly like a working one from outside",
                        Tier.CORE);
            }
            String tipAfterAccept = git.line(authRef, "rev-parse", "--verify", targetRef);
            if (!tipAfterAccept.equals(commit)) {
                return Check.fail("hook.selfcheck",
                        "sandbox push reported success but the ref did not move to " + commit, Tier.CORE);
            }

            // --- negative path: no push-option, expect REJECT and an unmoved tip ---
            Files.writeString(work.resolve("change2.txt"), "change2\n", StandardCharsets.UTF_8);
            git.must(workRef, "add", "-A");
            String tree2 = git.line(workRef, "write-tree");
            String commit2 = git.must(work, identityEnv(),
                    "commit-tree", tree2, "-p", commit, "-m", "selfcheck-no-token").stdout().trim();
            ProcessRunner.ProcRun reject = git.run(work, Map.of(),
                    "push", auth.toString(), commit2 + ":" + targetRef);
            if (reject.ok()) {
                return Check.fail("hook.selfcheck",
                        "sandbox push WITHOUT an approval was ACCEPTED — the gate is not enforcing", Tier.CORE);
            }
            String tipAfterReject = git.line(authRef, "rev-parse", "--verify", targetRef);
            if (!tipAfterReject.equals(commit)) {
                return Check.fail("hook.selfcheck",
                        "unapproved push moved the sandbox tip to " + tipAfterReject, Tier.CORE);
            }

            return Check.pass("hook.selfcheck",
                    "sandbox ACCEPT+REJECT verified with the generated hook; real hook verified by sha256", Tier.CORE);
        } catch (IOException e) {
            return Check.fail("hook.selfcheck", "self-check could not run: " + e.getMessage(), Tier.CORE);
        } catch (RuntimeException e) {
            return Check.fail("hook.selfcheck", "self-check failed: " + e.getMessage(), Tier.CORE);
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(sandbox);
        }
    }

    /**
     * F5 + N4: the engine binary must be runnable and its flag surface must still expose what the
     * adapter needs. prism 0.5.0 has no {@code --version} flag; {@code prism version} is the probe
     * (doc/p2-schema-核对.md §2 deviation 2). The {@code review commit --help} output is checked for
     * the exact flags the adapter builds its argv with, so a flag drift across versions becomes a
     * startup failure (exit 22) rather than a runtime exit 2 that looks like a transient reject.
     */
    private Check checkEngineBinary() {
        GateConfig.EngineConfig engine = config.engine();
        if (processRunner == null) {
            return Check.fail("engine.binary",
                    "no ProcessRunner wired into preflight — engine checks cannot run", Tier.ENGINE);
        }
        java.time.Duration probeTimeout = java.time.Duration.ofSeconds(15);
        String binary = engine.cmd();

        ProcessRunner.ProcRun version = processRunner.run(
                java.util.List.of(binary, "version"), null, java.util.Map.of(), probeTimeout);
        if (version.timedOut() || version.exitCode() != 0) {
            return Check.fail("engine.binary",
                    "cannot execute `" + binary + " version` (exit=" + version.exitCode()
                            + ", timedOut=" + version.timedOut() + "): " + version.stderrFirstLine()
                            + " (F5: engine binary missing or not runnable)",
                    Tier.ENGINE);
        }

        ProcessRunner.ProcRun help = processRunner.run(
                java.util.List.of(binary, "review", "commit", "--help"),
                null, java.util.Map.of(), probeTimeout);
        String helpText = help.stdout() + "\n" + help.stderr();
        java.util.List<String> requiredFlags = java.util.List.of(
                "--parent", "--provider", "--model", "--format", "--fail-on");
        java.util.List<String> missing = new ArrayList<>();
        for (String flag : requiredFlags) {
            if (!helpText.contains(flag)) {
                missing.add(flag);
            }
        }
        if (!missing.isEmpty()) {
            return Check.fail("engine.binary",
                    "prism `review commit --help` is missing flags " + missing
                            + " (N4: argv drift would cause runtime exit 2); upgrade or realign the adapter",
                    Tier.ENGINE);
        }
        return Check.pass("engine.binary",
                "prism version=" + version.stdout().trim() + "; required flags present", Tier.ENGINE);
    }

    private static Map<String, String> identityEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_AUTHOR_NAME", "gate");
        env.put("GIT_AUTHOR_EMAIL", "gate@localhost");
        env.put("GIT_AUTHOR_DATE", "1700000000 +0000");
        env.put("GIT_COMMITTER_NAME", "gate");
        env.put("GIT_COMMITTER_EMAIL", "gate@localhost");
        env.put("GIT_COMMITTER_DATE", "1700000000 +0000");
        return env;
    }
}
