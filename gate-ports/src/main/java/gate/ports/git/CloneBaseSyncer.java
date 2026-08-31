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
     * Pulls the human workspace's {@code baseRef} branch into the authoritative repo before a
     * ticket clone syncs against it (T-118 补环：工作区 → 权威镜像). Without this leg the mirror
     * never sees commits made in the registered workspace, and every sync truthfully reports the
     * clone up to date with a base that is itself stale (the T-125 bug: a fresh doc commit in the
     * source repo could never reach the clone).
     *
     * <p>The import is read-only on the workspace and fail-open: any refusal degrades to
     * {@link ImportKind#SKIPPED} with a reason and the caller proceeds against the mirror as-is.
     */
    ImportResult importWorkspaceBase(RepoRef workspaceRepo, RepoRef authRepo, String baseRef);

    /** How the authoritative base branch came to sit on its post-import tip. */
    enum ImportKind {
        /** Mirror already matched the workspace branch. */
        UP_TO_DATE,
        /** Mirror fast-forwarded onto new workspace commits. */
        FAST_FORWARDED,
        /** Mirror adopted the workspace history wholesale (the seed-only line was abandoned). */
        ADOPTED,
        /** Nothing was moved — see {@code skippedReason}. */
        SKIPPED
    }

    record ImportResult(ImportKind kind, String tip, String skippedReason) {
    }

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
     * @param importKind   lowercase {@link ImportKind} of the workspace import performed just
     *                     before this sync, {@code null} when no import was attempted
     * @param importReason  human-readable refusal reason, only when {@code importKind == "skipped"}
     */
    record Report(String status, int behind, String fromTip, String toTip, boolean branchMoved,
                  List<String> conflicts, boolean stashKept, String skippedReason,
                  String importKind, String importReason) {

        /** Legacy 8-field shape: no workspace import was attempted. */
        public Report(String status, int behind, String fromTip, String toTip, boolean branchMoved,
                      List<String> conflicts, boolean stashKept, String skippedReason) {
            this(status, behind, fromTip, toTip, branchMoved, conflicts, stashKept, skippedReason, null, null);
        }

        /** The same sync outcome, annotated with the workspace-import result that preceded it. */
        public Report withImport(ImportResult imported) {
            if (imported == null) {
                return this;
            }
            String kind = imported.kind().name().toLowerCase(java.util.Locale.ROOT);
            String reason = imported.kind() == ImportKind.SKIPPED ? imported.skippedReason() : null;
            return new Report(status, behind, fromTip, toTip, branchMoved, conflicts, stashKept,
                    skippedReason, kind, reason);
        }
    }
}
