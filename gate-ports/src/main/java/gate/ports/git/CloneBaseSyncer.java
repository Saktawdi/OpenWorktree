package gate.ports.git;

import gate.domain.git.RepoRef;
import java.util.List;

/**
 * Fast-forwards a ticket clone (and its authoritative branch) onto the current tip of the
 * project's base branch, replaying uncommitted worktree changes (T-118 基座同步).
 *
 * <p>The reason this exists: a ticket clone is cut from the base branch tip at ticket-creation
 * time and the gate never touches it again. Every day the ticket sits in the queue the reviewed
 * diff drifts further from what main actually looks like, and the eventual integration merge is
 * resolved — unreviewed — in the human workspace. Syncing the base early moves conflict resolution
 * into the clone, where the agent fixes it and the fix flows through the next presubmit.
 *
 * <p>Safety rules (mirroring the gate's fail-closed posture):
 * <ul>
 *   <li>the authoritative branch only ever <em>fast-forwards</em> to the base tip (server-side
 *       CAS {@code update-ref}, same bootstrap nature as {@code ensureBranch}); a branch carrying
 *       commits not yet merged into base is never moved;</li>
 *   <li>a clone whose history has diverged from base is never rewritten — the caller gets a
 *       {@code skipped} report instead (the content-identical squash case is the one exception:
 *       {@code reset --soft} re-points the branch without touching a single byte of content);</li>
 *   <li>uncommitted work (including untracked files) is preserved: stashed before the reset and
 *       popped back after, conflicts left in the worktree for the agent to resolve.</li>
 * </ul>
 *
 * <p>Callers must hold the ticket lock: this mutates the clone worktree and refs that presubmit
 * and live sessions also touch.
 */
public interface CloneBaseSyncer {

    /**
     * @param allowDirty when {@code false}, a clone with uncommitted changes (including untracked)
     *        is skipped untouched instead of stashed — the session-start auto-sync uses this
     */
    Report sync(RepoRef cloneRepo, RepoRef authRepo, String targetRef, String baseRef, boolean allowDirty);

    /**
     * @param status       {@code synced} (fast-forwarded), {@code healed} (content-identical
     *                     divergence re-pointed via reset --soft), {@code up_to_date},
     *                     {@code skipped}
     * @param behind       commits of base not reachable from the clone HEAD, measured before sync
     * @param fromTip      clone HEAD before the sync
     * @param toTip        clone HEAD after the sync (== fromTip when up_to_date/skipped)
     * @param branchMoved  true when the authoritative targetRef was fast-forwarded
     * @param conflicts    paths left with conflict markers after the stash pop (empty otherwise)
     * @param stashKept    true when a stash entry survived (conflict or pop failure) and still
     *                     holds the pre-sync worktree state
     * @param skippedReason human-readable reason, only when {@code status == "skipped"}
     */
    record Report(String status, int behind, String fromTip, String toTip, boolean branchMoved,
                  List<String> conflicts, boolean stashKept, String skippedReason) {
    }
}
