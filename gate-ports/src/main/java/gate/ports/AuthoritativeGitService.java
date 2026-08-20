package gate.ports;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.PublishAuthorization;
import java.util.Optional;

/**
 * Authoritative Git CAS service (ADR-003, production-architecture §7).
 * The only place where old_oid -> new_oid is enforced atomically.
 * Local mode uses file-lock + ref-transaction; enterprise uses pre-receive hook.
 */
public interface AuthoritativeGitService {

    record CasResult(boolean success, boolean alreadyPublished, String observedOldOid, String observedNewOid, String reason) {}

    /**
     * Atomically: verify authorization signature/nonce/expiry, check actual_old == expected_old,
     * verify new_commit parent/tree/ref, then CAS update ref. Nonce consumption and ref update
     * must be atomic (same ref transaction).
     */
    CasResult casPublish(RepoRef authRepo, String targetRef, ObjectId expectedOldOid, ObjectId newCommitOid,
                         ObjectId treeHash, PublishAuthorization authorization);

    /** Query actual tip for reconcile. */
    Optional<ObjectId> tip(RepoRef authRepo, String targetRef);

    /** Check if a nonce has been consumed (refs/gate/authorizations/<nonce> exists). */
    boolean isNonceConsumed(RepoRef authRepo, String nonce);

    /** Returns true if new_commit is ancestor of tip (published check). */
    boolean isPublished(RepoRef authRepo, String targetRef, ObjectId newCommitOid);
}
