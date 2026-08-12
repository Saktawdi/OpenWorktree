package gate.domain.publish;

import gate.domain.git.ObjectId;

/**
 * The exact ref transition an approval authorises (架构落地执行文档 §6.2).
 *
 * <p>All four fields are bound, not just {@code tree}. Binding {@code old} is what closes the
 * B15 "snapshot laundering" attack: grafting an already-reviewed tree onto a different parent
 * silently reverts every intermediate change, and neither a tree check nor a fast-forward check
 * catches it — only {@code parent == old} does.
 *
 * <p>Binding {@code tree} alone is the refuted 执行文档 §3.4 design (B9): an agent can honestly
 * compute the tree of its own unreviewed commit and satisfy the equality.
 */
public record ApprovalGrant(String ref, ObjectId oldCommit, ObjectId newCommit, ObjectId tree) {

    public ApprovalGrant {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref must not be blank");
        }
        if (oldCommit == null || newCommit == null || tree == null) {
            throw new IllegalArgumentException("old/new/tree must not be null");
        }
        if (newCommit.isZero()) {
            throw new IllegalArgumentException("refusing to authorise a ref deletion");
        }
    }

    /**
     * Serialises to the on-disk record format the hook greps with {@code grep -qx}.
     * Written with LF only; a CR would make every {@code grep -qx} miss and turn the gate into a
     * permanent-reject (the silent-rejection failure mode, spike-结论 §2.4).
     */
    public String toRecordText() {
        return "ref=" + ref + "\n"
                + "old=" + oldCommit.hex() + "\n"
                + "new=" + newCommit.hex() + "\n"
                + "tree=" + tree.hex() + "\n";
    }
}
