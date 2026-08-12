package gate.domain.publish;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import java.time.Instant;

/**
 * Write-ahead record of an intended publish (架构落地执行文档 §7.2, §7.4 I1).
 *
 * <p>Persisted with {@code synchronous=FULL} <em>before</em> {@code commit-tree} runs, so every
 * crash point C0–C5 has a persistent anchor and recovery never has to guess. {@code commitSha} is
 * back-filled once the object exists; it is the <em>effect identity</em> and carries a UNIQUE
 * constraint, which is what makes a second publish a no-op instead of a second commit.
 *
 * <p>Only {@code status}/{@code commitSha}/{@code observed*}/{@code finishedAt} may ever change
 * (I2). Everything else is immutable after insert.
 */
public record PublishIntent(
        long id,
        String ticketNo,
        int reviewRound,
        ObjectId treeHash,
        ObjectId baseCommit,
        String targetRef,
        String commitMessage,
        CommitIdentity author,
        CommitIdentity committer,
        ApprovalId approvalId,
        ObjectId commitSha,
        PublishStatus status,
        String observedRefBefore,
        String observedRefAfter,
        Instant createdAt,
        Instant finishedAt,
        RepoRef cloneRepo,
        RepoRef authRepo) {

    public PublishIntent {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (reviewRound < 1) {
            throw new IllegalArgumentException("reviewRound must be >= 1");
        }
        if (treeHash == null || baseCommit == null) {
            throw new IllegalArgumentException("treeHash/baseCommit must not be null");
        }
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef must not be blank");
        }
        if (commitMessage == null || commitMessage.isBlank()) {
            throw new IllegalArgumentException("commitMessage must not be blank");
        }
        if (author == null || committer == null) {
            throw new IllegalArgumentException("author/committer must not be null");
        }
        if (approvalId == null) {
            throw new IllegalArgumentException("approvalId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public PublishIntent withCommitSha(ObjectId sha) {
        return new PublishIntent(id, ticketNo, reviewRound, treeHash, baseCommit, targetRef,
                commitMessage, author, committer, approvalId, sha, status, observedRefBefore,
                observedRefAfter, createdAt, finishedAt, cloneRepo, authRepo);
    }

    public PublishIntent withOutcome(PublishStatus newStatus, String before, String after, Instant finished) {
        return new PublishIntent(id, ticketNo, reviewRound, treeHash, baseCommit, targetRef,
                commitMessage, author, committer, approvalId, commitSha, newStatus, before,
                after, createdAt, finished, cloneRepo, authRepo);
    }

    public ApprovalGrant toGrant() {
        if (commitSha == null) {
            throw new IllegalStateException("cannot build an approval grant before commit_sha exists");
        }
        return new ApprovalGrant(targetRef, baseCommit, commitSha, treeHash);
    }
}
