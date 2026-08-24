package gate.ports.store;

import gate.domain.git.ObjectId;
import gate.domain.publish.ApprovalId;
import gate.domain.publish.CommitIdentity;
import gate.domain.publish.PublishIntent;
import gate.domain.publish.PublishStatus;
import gate.domain.snapshot.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@code publish_intent} — the write-ahead log of the publish path (§7.2/§7.4).
 *
 * <p>{@code insertPending} must be durable ({@code synchronous=FULL}) before {@code commit-tree}
 * runs. Only status / commit_sha / observed_* / finished_at are mutable afterwards (I2).
 */
public interface PublishIntentRepository {

    PublishIntent insertPending(
            String ticketNo,
            int reviewRound,
            Snapshot snapshot,
            String commitMessage,
            CommitIdentity author,
            CommitIdentity committer,
            ApprovalId approvalId,
            Instant now,
            gate.domain.git.RepoRef cloneRepo,
            gate.domain.git.RepoRef authRepo);

    Optional<PublishIntent> find(String ticketNo, int reviewRound, ObjectId treeHash);

    Optional<PublishIntent> findById(long id);

    void updateCommitSha(long id, ObjectId commitSha);

    void updateOutcome(long id, PublishStatus status, String observedBefore, String observedAfter, Instant finishedAt);

    /** Everything still {@code PENDING}; the input to {@code reconcile}. */
    List<PublishIntent> findPending();

    List<PublishIntent> findByTicket(String ticketNo);
}
