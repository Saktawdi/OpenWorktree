package gate.domain.snapshot;

import gate.domain.git.ObjectId;
import java.util.List;

/**
 * An immutable capture of an agent clone's worktree (架构落地执行文档 §3.2).
 *
 * <p>Produced with a brand-new empty temporary index and explicit {@code core.autocrlf=false}
 * (ADR-5): the agent's real index must be byte-identical before and after capture, and the same
 * git configuration must be used when the tree is recomputed at publish time — measured fact
 * from 架构落地执行文档 §0.1: the same bytes yield a different {@code tree_hash} under a
 * different {@code core.autocrlf}.
 *
 * @param treeHash    the immutable anchor for review and publish
 * @param baseCommit  tip of {@code refs/heads/<targetRef>} in {@code auth.git} at capture time
 * @param baseTree    {@code baseCommit^{tree}}, carried explicitly so the empty-diff check is an
 *                    exact hash comparison. {@code commit-tree} happily builds a commit whose tree
 *                    equals its parent's tree (measured, §0.1), so an empty round must be refused
 *                    by the application — and refused without consuming a review round (§3.2)
 * @param targetRef   full ref name, e.g. {@code refs/heads/main}
 * @param changedPaths repo-relative, '/'-separated paths differing between base tree and this tree
 * @param diff        full textual diff {@code base_commit..tree}, stored via BlobStore by the caller
 * @param integrity   R1–R5 outcome; blockers mean the snapshot must not be persisted
 */
public record Snapshot(
        ObjectId treeHash,
        ObjectId baseCommit,
        ObjectId baseTree,
        String targetRef,
        List<String> changedPaths,
        String diff,
        CaptureIntegrityReport integrity) {

    public Snapshot {
        if (treeHash == null) {
            throw new IllegalArgumentException("treeHash must not be null");
        }
        if (baseCommit == null) {
            throw new IllegalArgumentException("baseCommit must not be null");
        }
        if (baseTree == null) {
            throw new IllegalArgumentException("baseTree must not be null");
        }
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef must not be blank");
        }
        if (diff == null) {
            throw new IllegalArgumentException("diff must not be null");
        }
        if (integrity == null) {
            throw new IllegalArgumentException("integrity must not be null");
        }
        changedPaths = List.copyOf(changedPaths);
    }

    /**
     * True when this capture changes nothing relative to the base commit.
     *
     * <p>Must be refused at presubmit and must not consume a review round: {@code commit-tree} does
     * not reject a tree equal to its parent's, so nothing downstream would notice.
     */
    public boolean isEmptyDiff() {
        return treeHash.equals(baseTree);
    }
}
