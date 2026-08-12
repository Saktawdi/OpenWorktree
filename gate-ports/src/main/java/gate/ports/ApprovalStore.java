package gate.ports;

import gate.domain.publish.ApprovalGrant;
import gate.domain.publish.ApprovalId;

/**
 * Issues and inspects one-shot approval records (架构落地执行文档 §6.2).
 *
 * <p>The record lives <em>outside</em> {@code auth.git} and its path is baked into the generated
 * hook. That is the single location where an OS ACL could later create a real privilege boundary
 * without any refactoring.
 */
public interface ApprovalStore {

    /**
     * Reserves a fresh 128-bit id without writing anything.
     *
     * <p>Needed because {@code publish_intent.approval_id} is NOT NULL and the intent row must be
     * durable <em>before</em> {@code commit-tree} runs (I1), while the record itself can only be
     * written once {@code commit_sha} exists — the grant binds {@code new=commit_sha}.
     */
    ApprovalId allocate();

    /** Writes {@code approvals/<id>} with LF line endings and no BOM. Fails if the id already exists. */
    void issue(ApprovalId id, ApprovalGrant grant);

    /**
     * Re-writes an existing id after a crash, when the intent's {@code approval_id} is already
     * persisted and immutable (I2) but the record file was never written or was lost.
     *
     * <p>Refuses if the id is already consumed: a consumed record means receive-pack accepted a push
     * under it, so re-issuing could authorise a second ref transition with a one-shot credential.
     */
    void reissue(ApprovalId id, ApprovalGrant grant);

    boolean isConsumed(ApprovalId id);

    boolean isLive(ApprovalId id);
}
