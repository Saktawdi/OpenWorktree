package gate.adapters.git;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.ports.git.CloneBaseSyncer;
import gate.ports.infra.ProcessRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only {@link CloneBaseSyncer} implementation: real git binary, real refs (架构落地执行文档
 * ADR-1/ADR-5, T-118 基座同步).
 *
 * <p>Mutation order is chosen so every crash point converges on the next run:
 * <ol>
 *   <li>fetch base + target refs into {@code refs/gate-sync/*} (objects land locally, no worktree
 *       or ref of consequence is touched);</li>
 *   <li>stash the worktree (only when dirty and allowed);</li>
 *   <li>CAS-fast-forward the authoritative targetRef to the base tip (fails harmlessly if a
 *       concurrent writer moved it);</li>
 *   <li>{@code reset --hard} the clone onto the new tip;</li>
 *   <li>pop the stash back; conflicts stay in the worktree and the stash entry survives.</li>
 * </ol>
 * A crash between 3 and 4 leaves the clone behind, which is exactly the state this sync repairs —
 * idempotent by construction.
 */
public final class GitCliBaseSyncer implements CloneBaseSyncer {

    private static final String SYNC_TARGET_REF = "refs/gate-sync/target";
    private static final String SYNC_BASE_REF = "refs/gate-sync/base";

    private final GitCli git;

    public GitCliBaseSyncer(GitCli git) {
        this.git = git;
    }

    @Override
    public Report sync(RepoRef cloneRepo, RepoRef authRepo, String targetRef, String baseRef,
                       boolean allowDirty) {
        assertNoMergeInProgress(cloneRepo);

        String authTip = resolve(authRepo, targetRef);
        if (authTip == null) {
            return skipped("authoritative repo has no " + targetRef + " branch");
        }
        String mainTip = resolve(authRepo, baseRef);
        if (mainTip == null) {
            return skipped("authoritative repo has no " + baseRef + " branch");
        }

        // Bring base/target objects into the clone first; every later comparison runs locally.
        ProcessRunner.ProcRun fetch = git.run(cloneRepo, "fetch", authRepo.pathString(),
                "+" + targetRef + ":" + SYNC_TARGET_REF, "+" + baseRef + ":" + SYNC_BASE_REF);
        if (!fetch.ok()) {
            return skipped("fetch failed: " + fetch.stderrFirstLine());
        }
        String cloneHead = resolve(cloneRepo, "HEAD");
        if (cloneHead == null) {
            return skipped("clone has no HEAD commit");
        }

        int behind = behindCount(cloneRepo, cloneHead, SYNC_BASE_REF);
        boolean contentEqualsBase = treesEqual(cloneRepo, cloneHead, SYNC_BASE_REF);
        String targetTipInClone = resolve(cloneRepo, SYNC_TARGET_REF);

        if (behind == 0 && cloneHead.equals(targetTipInClone)) {
            return upToDate(cloneHead);
        }
        if (behind == 0 && !contentEqualsBase) {
            // Clone already contains every base commit and its content differs from base —
            // normal in-flight state (worktree/commits ahead of an unmoved base).
            return upToDate(cloneHead);
        }
        if (contentEqualsBase) {
            // Content-identical divergence (e.g. a squash recovery commit vs the published one):
            // re-point the clone branch without touching index or worktree — zero content moves.
            // behind may be 0 or > 0 here; either way the committed content equals the base tip,
            // so nothing of value can be lost by the soft re-point.
            return healSquashDivergence(cloneRepo, authRepo, targetRef, mainTip, behind, cloneHead);
        }

        // behind > 0: clone is behind base. Both the ticket branch and the clone must sit on the
        // base tip afterwards, and both moves must be fast-forwards.
        if (!isAncestor(cloneRepo, cloneHead, SYNC_BASE_REF)) {
            return skipped(behind, cloneHead, "clone 与基分支历史分叉（存在基分支不含的提交），需要人工处理");
        }
        String authTargetNow = resolve(authRepo, targetRef);
        if (!isAncestor(authRepo, authTargetNow, mainTip)) {
            return skipped(behind, cloneHead, "authoritative branch " + targetRef
                    + " carries commits not merged into " + baseRef + "; refusing to move it");
        }

        boolean dirty = isDirty(cloneRepo);
        if (dirty && !allowDirty) {
            return skipped(behind, cloneHead, "clone 工作区有未提交改动；为避免干扰已跳过同步（可手动同步重放改动）");
        }
        boolean stashed = false;
        if (dirty) {
            ProcessRunner.ProcRun stash = git.run(cloneRepo,
                    "-c", "user.name=gate", "-c", "user.email=gate@localhost",
                    "stash", "push", "-u", "-m", "gate-sync-base");
            stashed = stash.ok();
            if (!stashed) {
                return skipped(behind, cloneHead, "stash 失败，未做任何改动: " + stash.stderrFirstLine());
            }
        }

        ProcessRunner.ProcRun move = git.run(authRepo, "update-ref", targetRef, mainTip, authTip);
        if (!move.ok()) {
            undoStash(cloneRepo, stashed);
            return skipped(behind, cloneHead,
                    "authoritative branch move failed (moved concurrently?): " + move.stderrFirstLine());
        }

        ProcessRunner.ProcRun reset = git.run(cloneRepo, "reset", "--hard", SYNC_BASE_REF);
        if (!reset.ok()) {
            undoStash(cloneRepo, stashed);
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot reset clone onto base tip: " + reset.stderrFirstLine());
        }

        List<String> conflicts = new ArrayList<>();
        boolean stashKept = false;
        if (stashed) {
            ProcessRunner.ProcRun pop = git.run(cloneRepo, "stash", "pop");
            if (!pop.ok()) {
                conflicts = conflictedPaths(cloneRepo);
                stashKept = true;
            }
        }
        String toTip = resolve(cloneRepo, "HEAD");
        return new Report("synced", behind, cloneHead, toTip, true, List.copyOf(conflicts), stashKept, null);
    }

    /**
     * Content-identical divergence: re-point the clone branch onto the base tip ({@code reset
     * --soft} — index and worktree are untouched, so this is safe even for a dirty clone) and
     * fast-forward the authoritative branch alongside. Refuses when the authoritative branch
     * cannot fast-forward — a half-healed clone would still fail presubmit.
     */
    private Report healSquashDivergence(RepoRef cloneRepo, RepoRef authRepo, String targetRef,
                                        String mainTip, int behind, String cloneHead) {
        String authTip = resolve(authRepo, targetRef);
        if (!isAncestor(authRepo, authTip, mainTip)) {
            return skipped(behind, cloneHead, "authoritative branch " + targetRef
                    + " carries commits not merged into the base branch; refusing to move it");
        }
        ProcessRunner.ProcRun move = git.run(authRepo, "update-ref", targetRef, mainTip, authTip);
        if (!move.ok()) {
            return skipped(behind, cloneHead,
                    "authoritative branch move failed (moved concurrently?): " + move.stderrFirstLine());
        }
        ProcessRunner.ProcRun reset = git.run(cloneRepo, "reset", "--soft", SYNC_BASE_REF);
        if (!reset.ok()) {
            return skipped(behind, cloneHead, "soft re-point failed: " + reset.stderrFirstLine());
        }
        return new Report("healed", behind, cloneHead, mainTip, true, List.of(), false, null);
    }

    private void undoStash(RepoRef cloneRepo, boolean stashed) {
        if (stashed) {
            git.run(cloneRepo, "stash", "pop");
        }
    }

    private Report upToDate(String cloneHead) {
        return new Report("up_to_date", 0, cloneHead, cloneHead, false, List.of(), false, null);
    }

    private Report skipped(String reason) {
        return new Report("skipped", 0, null, null, false, List.of(), false, reason);
    }

    private Report skipped(int behind, String fromTip, String reason) {
        return new Report("skipped", behind, fromTip, null, false, List.of(), false, reason);
    }

    /** Commits reachable from {@code tip} but not from {@code from}. */
    private int behindCount(RepoRef repo, String from, String tip) {
        ProcessRunner.ProcRun run = git.run(repo, "rev-list", "--count", from + ".." + tip);
        if (!run.ok()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "rev-list --count failed: " + run.stderrFirstLine());
        }
        return Integer.parseInt(run.stdout().trim());
    }

    private boolean isAncestor(RepoRef repo, String ancestor, String descendant) {
        ProcessRunner.ProcRun run = git.run(repo, "merge-base", "--is-ancestor", ancestor, descendant);
        int exit = run.exitCode();
        if (exit != 0 && exit != 1) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "merge-base --is-ancestor failed (exit=" + exit + "): " + run.stderrFirstLine());
        }
        return exit == 0;
    }

    /** True when the two commits hold identical trees (histories may differ). */
    private boolean treesEqual(RepoRef repo, String left, String right) {
        ProcessRunner.ProcRun run = git.run(repo, "diff", "--quiet", left, right);
        int exit = run.exitCode();
        if (exit != 0 && exit != 1) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "diff --quiet failed (exit=" + exit + "): " + run.stderrFirstLine());
        }
        return exit == 0;
    }

    private boolean isDirty(RepoRef cloneRepo) {
        ProcessRunner.ProcRun status = git.run(cloneRepo, "status", "--porcelain");
        if (!status.ok()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "git status failed: " + status.stderrFirstLine());
        }
        return !status.stdout().isBlank();
    }

    private List<String> conflictedPaths(RepoRef cloneRepo) {
        ProcessRunner.ProcRun run = git.run(cloneRepo, "diff", "--name-only", "--diff-filter=U");
        if (!run.ok()) {
            return List.of();
        }
        return run.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String resolve(RepoRef repo, String ref) {
        ProcessRunner.ProcRun run = git.run(repo, "rev-parse", "--verify", ref);
        return run.ok() ? run.stdout().trim() : null;
    }

    /** Merge / cherry-pick / rebase in progress cannot be expressed by a fast-forward sync. */
    private static void assertNoMergeInProgress(RepoRef cloneRepo) {
        for (String marker : List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD")) {
            if (java.nio.file.Files.exists(cloneRepo.path().resolve(".git").resolve(marker))) {
                throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                        "refusing base sync while " + marker + " exists");
            }
        }
    }
}
