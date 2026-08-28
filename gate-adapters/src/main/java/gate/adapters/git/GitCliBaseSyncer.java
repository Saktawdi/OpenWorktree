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
 * <p>The clone's landing commit is picked once, then reached in one hop: the authoritative branch
 * is fast-forwarded (server-side CAS) onto the base tip when possible and the clone follows — one
 * {@code reset} covers both "clone behind base" (ticket sat in the queue) and "clone behind its
 * own branch" (a publish advanced the branch, T-113 round-2 incident). When the branch cannot
 * fast-forward onto base, the branch tip itself is the target; the clone still catches up, the
 * base-freshness goal simply waits.
 *
 * <p>Mutation order is chosen so every crash point converges on the next run:
 * <ol>
 *   <li>fetch base + target refs into {@code refs/gate-sync/*} (objects land locally, no worktree
 *       or ref of consequence is touched);</li>
 *   <li>CAS-fast-forward the authoritative targetRef when base allows it (fails harmlessly if a
 *       concurrent writer moved it);</li>
 *   <li>stash the worktree (only when dirty and allowed);</li>
 *   <li>{@code reset --hard} the clone onto the final tip — or {@code reset --soft} for the
 *       content-identical divergence heal, which moves no worktree byte;</li>
 *   <li>pop the stash back; conflicts stay in the worktree and the stash entry survives.</li>
 * </ol>
 * A crash between 2 and 4 leaves the clone behind, which is exactly the state this sync repairs —
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

        int behindBase = behindCount(cloneRepo, cloneHead, SYNC_BASE_REF);

        // The final tip the clone must land on. The presubmit invariant is "clone HEAD == auth
        // targetRef tip"; base freshness is the secondary goal, so when the authoritative branch
        // can fast-forward onto the base tip we take the base tip (one hop covers both the
        // queue-stale and the just-published state), otherwise the branch tip itself (a publish
        // advanced it while main did not yet contain it — the normal in-flight shape).
        boolean branchCanFollowBase = isAncestor(authRepo, authTip, mainTip);
        boolean branchMoved = branchCanFollowBase && !authTip.equals(mainTip);
        String finalTip = branchCanFollowBase ? mainTip : authTip;
        String finalTipInClone = branchCanFollowBase ? SYNC_BASE_REF : SYNC_TARGET_REF;

        if (cloneHead.equals(finalTip)) {
            if (branchMoved) {
                ProcessRunner.ProcRun move = git.run(authRepo, "update-ref", targetRef, mainTip, authTip);
                if (!move.ok()) {
                    return skipped(behindBase, cloneHead,
                            "authoritative branch move failed (moved concurrently?): " + move.stderrFirstLine());
                }
                return new Report("synced", behindBase, cloneHead, cloneHead, true, List.of(), false, null);
            }
            return new Report("up_to_date", behindBase, cloneHead, cloneHead, false, List.of(), false, null);
        }

        // Classify the clone's relation to the final tip before anything is mutated, so every
        // skipped outcome below leaves repo state untouched.
        boolean fastForwardable = isAncestor(cloneRepo, cloneHead, finalTipInClone);
        boolean contentIdentical = !fastForwardable && treesEqual(cloneRepo, cloneHead, finalTipInClone);
        if (!fastForwardable && !contentIdentical) {
            return skipped(behindBase, cloneHead,
                    "clone 与工单分支/基分支历史分叉（存在对方都不包含的本地提交），需要人工处理");
        }

        boolean dirty = isDirty(cloneRepo);
        if (fastForwardable && dirty && !allowDirty) {
            return skipped(behindBase, cloneHead,
                    "clone 工作区有未提交改动；为避免干扰已跳过同步（可手动同步重放改动）");
        }
        boolean stashed = false;
        if (fastForwardable && dirty) {
            ProcessRunner.ProcRun stash = git.run(cloneRepo,
                    "-c", "user.name=gate", "-c", "user.email=gate@localhost",
                    "stash", "push", "-u", "-m", "gate-sync-base");
            stashed = stash.ok();
            if (!stashed) {
                return skipped(behindBase, cloneHead,
                        "stash 失败，未做任何改动: " + stash.stderrFirstLine());
            }
        }

        if (branchMoved) {
            ProcessRunner.ProcRun move = git.run(authRepo, "update-ref", targetRef, mainTip, authTip);
            if (!move.ok()) {
                undoStash(cloneRepo, stashed);
                return skipped(behindBase, cloneHead,
                        "authoritative branch move failed (moved concurrently?): " + move.stderrFirstLine());
            }
        }

        if (fastForwardable) {
            // Fast-forward the clone: covers the clone lagging its own branch (publish advanced
            // it) as well as the clone lagging base (ticket sat in the queue).
            ProcessRunner.ProcRun reset = git.run(cloneRepo, "reset", "--hard", finalTipInClone);
            if (!reset.ok()) {
                undoStash(cloneRepo, stashed);
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "cannot reset clone onto the sync target: " + reset.stderrFirstLine());
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
            return new Report("synced", behindBase, cloneHead, toTip, branchMoved,
                    List.copyOf(conflicts), stashKept, null);
        }

        // Content-identical divergence (e.g. a squash recovery commit vs the published one):
        // re-point the clone branch without touching index or worktree — zero content moves.
        ProcessRunner.ProcRun reset = git.run(cloneRepo, "reset", "--soft", finalTipInClone);
        if (!reset.ok()) {
            return skipped(behindBase, cloneHead, "soft re-point failed: " + reset.stderrFirstLine());
        }
        return new Report("healed", behindBase, cloneHead, finalTip, branchMoved, List.of(), false, null);
    }

    private void undoStash(RepoRef cloneRepo, boolean stashed) {
        if (stashed) {
            git.run(cloneRepo, "stash", "pop");
        }
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
