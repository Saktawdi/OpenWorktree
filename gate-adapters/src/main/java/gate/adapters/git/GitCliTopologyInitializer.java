package gate.adapters.git;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.publish.CommitIdentity;
import gate.ports.git.HookInstaller;
import gate.ports.git.TopologyInitializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the repository topology of 架构落地执行文档 §2.1 with the real git binary.
 *
 * <p>Ordering inside {@link #initAuthRepo} is deliberate and not interchangeable:
 *
 * <ol>
 *   <li>{@code init --bare}, then <b>explicitly</b> {@code symbolic-ref HEAD refs/heads/<target>}.
 *       git 2.37 defaults HEAD to {@code master}; skipping this yields clones sitting on an unborn
 *       branch, and {@code base_commit} becomes unresolvable (N1 — P0 tripped over this twice).</li>
 *   <li>{@code receive.advertisePushOptions=true}. Without it the client cannot even send a
 *       push-option: the push fails with {@code fatal: the receiving end does not support push
 *       options} before the hook runs, so the entire release path is silently dead (measured, exit
 *       128). {@code denyDeletes} and {@code denyNonFastForwards} are belt-and-braces behind the
 *       hook's own judgements ⑤ and fast-forward check.</li>
 *   <li><b>Seed the base commit, then install the hook.</b> The hook demands exactly one parent and
 *       an approval bound to {@code (ref, old, new, tree)}; a root commit has zero parents, so it can
 *       never pass. The base commit is therefore bootstrap infrastructure, not a ticket product —
 *       which is exactly the A1 accounting baseline: a ticket must add exactly one commit on top of
 *       it. Seeding after installation would be structurally impossible, and relaxing the
 *       single-parent judgement to allow it would dismantle the B15 defence.</li>
 * </ol>
 */
public final class GitCliTopologyInitializer implements TopologyInitializer {

    /** Fixed bootstrap identity, so a seeded repo is reproducible and independent of user config. */
    private static final CommitIdentity SEED_IDENTITY =
            new CommitIdentity("gate", "gate@localhost", "1700000000 +0000");

    private final GitCli git;
    private final HookInstaller hookInstaller;
    private final List<String> targetRefWhitelist;
    private final Path tempRoot;

    public GitCliTopologyInitializer(GitCli git, HookInstaller hookInstaller,
                                     List<String> targetRefWhitelist, Path tempRoot) {
        this.git = git;
        this.hookInstaller = hookInstaller;
        this.targetRefWhitelist = List.copyOf(targetRefWhitelist);
        this.tempRoot = tempRoot;
    }

    @Override
    public InitResult initAuthRepo(RepoRef authRepo, String targetRef, Path approvalsDir) {
        if (!targetRefWhitelist.contains(targetRef)) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "target ref " + targetRef + " is not in the configured whitelist " + targetRefWhitelist);
        }
        try {
            Files.createDirectories(authRepo.path());
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create " + authRepo.pathString(), e);
        }

        boolean alreadyInitialised = Files.exists(authRepo.path().resolve("HEAD"));
        if (!alreadyInitialised) {
            git.must(null, Map.of(), "init", "--bare", authRepo.pathString());
        }

        // N1: never rely on git's default branch name.
        git.must(authRepo, "symbolic-ref", "HEAD", targetRef);

        git.must(authRepo, "config", "receive.advertisePushOptions", "true");
        git.must(authRepo, "config", "receive.denyDeletes", "true");
        git.must(authRepo, "config", "receive.denyNonFastForwards", "true");
        // The hook is generated and its path must not be redirectable.
        git.run(authRepo, "config", "--unset-all", "core.hooksPath");

        ObjectId base = ensureSeeded(authRepo, targetRef);
        String hookSha = hookInstaller.install(authRepo, targetRefWhitelist, approvalsDir);
        return new InitResult(base, hookSha);
    }

    @Override
    public RepoRef createClone(RepoRef authRepo, String targetRef, Path cloneDir) {
        Path parent = cloneDir.toAbsolutePath().normalize().getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create clones root " + parent, e);
        }
        String branch = targetRef.substring("refs/heads/".length());
        // --no-hardlinks makes the object store physically independent, so objects from a rejected
        // push are discarded with the quarantine directory instead of lingering in auth.git (§2.2).
        git.must(parent, Map.of(),
                "clone", "--no-hardlinks", "--single-branch", "--branch", branch,
                authRepo.pathString(), cloneDir.toAbsolutePath().normalize().toString());

        RepoRef cloneRepo = RepoRef.of(cloneDir);
        // Repo-scoped identity: the gate must never depend on, or modify, the user's global config.
        git.must(cloneRepo, "config", "user.name", "agent");
        git.must(cloneRepo, "config", "user.email", "agent@localhost");
        git.must(cloneRepo, "config", "core.autocrlf", "false");
        git.must(cloneRepo, "config", "core.safecrlf", "false");

        // Under the pinned core.autocrlf=false a fresh clone must be byte-identical to HEAD.
        // A non-empty status means the checkout itself was mangled (e.g. the auth repo carries
        // .gitattributes eol rules), and every later working diff would degrade to pure
        // line-ending noise (the T-107 incident: 291 files, ±39k phantom lines). Fail fast
        // instead of seeding a poisoned workspace.
        var status = git.run(cloneRepo, "status", "--porcelain");
        if (status.ok() && !status.stdout().isBlank()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "fresh clone of "
                    + authRepo.pathString() + " into " + cloneDir + " is not clean: "
                    + status.stdout().lines().findFirst().orElse("?")
                    + " — checkout line-ending config disagrees with HEAD");
        }
        return cloneRepo;
    }

    /**
     * 确保权威库存在 {@code targetRef} 分支：缺失时从 {@code baseRef} tip 建支（服务端
     * {@code update-ref}，性质同种子提交的 bootstrap，不经 pre-receive），已存在则原样返回。
     */
    @Override
    public void ensureBranch(RepoRef authRepo, String targetRef, String baseRef) {
        var existing = git.run(authRepo, "rev-parse", "--verify", targetRef);
        if (existing.ok()) {
            return;
        }
        String baseTip = git.line(authRepo, "rev-parse", "--verify", baseRef);
        git.must(authRepo, "update-ref", targetRef, baseTip.trim());
    }

    /**
     * Seeds the target branch with a single bootstrap commit if it does not exist yet.
     *
     * <p>Uses a throwaway repository rather than a clone of the (still empty) bare repo, so nothing
     * depends on how git reports an empty clone.
     */
    private ObjectId ensureSeeded(RepoRef authRepo, String targetRef) {
        var existing = git.run(authRepo, "rev-parse", "--verify", targetRef);
        if (existing.ok()) {
            return ObjectId.of(existing.stdout().trim());
        }

        String branch = targetRef.substring("refs/heads/".length());
        Path seedDir = tempRoot.resolve("seed-" + System.nanoTime());
        try {
            Files.createDirectories(seedDir);
            git.must(seedDir.getParent(), Map.of(), "init", "-b", branch, seedDir.toString());
            RepoRef seed = RepoRef.of(seedDir);
            git.must(seed, "config", "user.name", SEED_IDENTITY.name());
            git.must(seed, "config", "user.email", SEED_IDENTITY.email());
            git.must(seed, "config", "core.autocrlf", "false");

            Files.writeString(seedDir.resolve(".gitkeep"),
                    "seeded by gate init; the gate requires a base commit because the pre-receive hook\n"
                            + "demands exactly one parent, so a root commit can never pass the gate.\n",
                    StandardCharsets.UTF_8);
            git.must(seed, "add", "-A");

            Map<String, String> env = new LinkedHashMap<>();
            env.put("GIT_AUTHOR_NAME", SEED_IDENTITY.name());
            env.put("GIT_AUTHOR_EMAIL", SEED_IDENTITY.email());
            env.put("GIT_AUTHOR_DATE", SEED_IDENTITY.date());
            env.put("GIT_COMMITTER_NAME", SEED_IDENTITY.name());
            env.put("GIT_COMMITTER_EMAIL", SEED_IDENTITY.email());
            env.put("GIT_COMMITTER_DATE", SEED_IDENTITY.date());
            git.must(seedDir, env, "commit", "-m", "gate: seed base commit");

            git.must(seedDir, Map.of(), "push", authRepo.pathString(), "HEAD:" + targetRef);

            return ObjectId.of(git.line(authRepo, "rev-parse", "--verify", targetRef));
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot seed base commit", e);
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(seedDir);
        }
    }
}
