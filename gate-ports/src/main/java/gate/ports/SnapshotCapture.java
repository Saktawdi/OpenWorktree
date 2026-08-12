package gate.ports;

import gate.domain.git.RepoRef;
import gate.domain.snapshot.Snapshot;

/**
 * Freezes an agent clone's worktree into an immutable tree (架构落地执行文档 §3.2/§3.3).
 *
 * <p>Exactly one production implementation, {@code GitCliSnapshot} (R-PORT + ADR-1): the gate's
 * correctness claim is "the tree I reviewed is the tree that landed", and the landing side is
 * evaluated by a C-git hook. A second implementation would mean two independent implementations of
 * {@code .gitattributes}/{@code autocrlf}/mode/symlink/ignore semantics deciding a core invariant.
 */
public interface SnapshotCapture {

    /**
     * @param cloneRepo agent clone; must be an independent clone, never a linked worktree (§2.2)
     * @param authRepo  authoritative bare repo, read only, to resolve {@code base_commit}
     * @param targetRef full ref name, e.g. {@code refs/heads/main}
     */
    Snapshot capture(RepoRef cloneRepo, RepoRef authRepo, String targetRef);

    /**
     * Rebuilds the immutable {@link Snapshot} for an already-recorded presubmit round, without
     * touching the worktree.
     *
     * <p>Used by {@code review} and {@code publish}: both need the snapshot's {@code changedPaths} and
     * {@code diff} to feed {@code GatePolicy}, but neither may re-run {@code add -A} — that would
     * fold in any post-presubmit worktree change and defeat the TOCTOU check. All values are derived
     * from the recorded {@code treeHash}/{@code baseCommit} plus the stored diff text.
     */
    Snapshot rebuild(RepoRef cloneRepo, String targetRef,
                     gate.domain.git.ObjectId treeHash, gate.domain.git.ObjectId baseCommit, String diff);
}
