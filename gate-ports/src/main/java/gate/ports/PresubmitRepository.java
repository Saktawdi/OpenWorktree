package gate.ports;

import gate.domain.git.ObjectId;
import gate.domain.snapshot.Snapshot;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for {@code presubmit} rows, and the round counter.
 *
 * <p>{@code (ticket_no, review_round, tree_hash)} is UNIQUE. A round is allocated only after every
 * validation passed, so an empty diff cannot consume one (§7.3).
 */
public interface PresubmitRepository {

    record PresubmitRow(
            long id,
            String ticketNo,
            int reviewRound,
            ObjectId treeHash,
            ObjectId baseCommit,
            String targetRef,
            String diffBlobPath,
            long diffBytes,
            String diffSha256,
            Instant createdAt) {
    }

    /** @return the next round number for this ticket, i.e. {@code max(round) + 1}, starting at 1 */
    int nextRound(String ticketNo);

    PresubmitRow insert(String ticketNo, int round, Snapshot snapshot, gate.domain.blob.BlobRef diff, Instant now);

    Optional<PresubmitRow> find(String ticketNo, int round);

    Optional<PresubmitRow> findLatest(String ticketNo);
}
