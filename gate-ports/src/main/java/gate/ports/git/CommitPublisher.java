package gate.ports.git;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.PublishAuthorization;
import gate.domain.publish.PublishIntent;

/**
 * Builds the dangling commit, pins it against gc, and pushes it through the gate
 * (架构落地执行文档 §3.5/§7). Exactly one production implementation: {@code GitCliPublisher}.
 */
public interface CommitPublisher {

    /**
     * {@code git commit-tree} with author/committer/date pinned via environment variables, so the
     * same input always yields the same SHA (§7.4 I1/I5). Crash recovery at C2 depends on this.
     */
    ObjectId buildCommit(PublishIntent intent);

    /** Pins the dangling commit under {@code refs/gate/<ticket>/<round>} so gc cannot collect it. */
    void pinGateRef(RepoRef cloneRepo, String ticketNo, int round, ObjectId commit);

    /**
     * Pushes with {@code --push-option=gate-approval=<id>}.
     *
     * <p>Requires a {@link PublishAuthorization}, which only {@code GatePolicy} can mint — that
     * parameter is the compile-time reason "publish without a decision" is unreachable (§8.2).
     */
    PublishOutcome publish(PublishIntent intent, PublishAuthorization authorization);

    /** Recomputes the worktree tree with the same pinned git config, for the TOCTOU re-check. */
    ObjectId recomputeTree(RepoRef cloneRepo);

    /** Ensure commit object reachable in auth bare repo (push to tmp ref) before CAS update-ref. */
    default void ensureObjectInAuth(RepoRef cloneRepo, RepoRef authRepo, ObjectId commit) {}

    record PublishOutcome(boolean accepted, int exitCode, String stdout, String stderr) {
    }
}
