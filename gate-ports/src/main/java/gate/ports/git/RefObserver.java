package gate.ports.git;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import java.util.Optional;

/**
 * Read-only observation of the authoritative repo (架构落地执行文档 §7).
 *
 * <p>This is how "is it published?" is answered — never from the DB, which is explicitly not the
 * source of truth (I4). The predicate matches {@code commit_sha}, never {@code tree_hash}: two
 * different commits can carry the same tree, and matching the tree would silently mark an
 * unpublished intent as published (ADR-4).
 */
public interface RefObserver {

    Optional<ObjectId> tip(RepoRef repo, String ref);

    boolean isAncestor(RepoRef repo, ObjectId maybeAncestor, ObjectId tip);

    /** {@code published(intent) := commit_sha != null AND merge-base --is-ancestor commit_sha target}. */
    default boolean published(RepoRef repo, String targetRef, ObjectId commitSha) {
        if (commitSha == null) {
            return false;
        }
        return tip(repo, targetRef).map(tip -> isAncestor(repo, commitSha, tip)).orElse(false);
    }

    /** {@code git rev-list --count <ref>}; used by the A1/A4 assertions. */
    long countCommits(RepoRef repo, String ref);
}
