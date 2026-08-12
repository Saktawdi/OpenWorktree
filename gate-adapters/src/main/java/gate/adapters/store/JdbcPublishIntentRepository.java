package gate.adapters.store;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.publish.ApprovalId;
import gate.domain.publish.CommitIdentity;
import gate.domain.publish.PublishIntent;
import gate.domain.publish.PublishStatus;
import gate.domain.snapshot.Snapshot;
import gate.ports.PublishIntentRepository;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-backed {@code publish_intent} store — the write-ahead log of the publish path.
 *
 * <p>Two design points worth stating:
 *
 * <p><b>No repo paths in the table.</b> The DDL is kept verbatim from §7.2, so the clone path is
 * re-joined from {@code ticket.clone_path} and the authoritative path comes from configuration. That
 * also means a moved project directory is repaired by fixing config, not by rewriting history rows.
 *
 * <p><b>Updates are narrow by construction.</b> Only {@code status}, {@code commit_sha},
 * {@code observed_*} and {@code finished_at} have update statements; every other column is
 * insert-only (invariant I2). The absence of an UPDATE for them is the enforcement.
 */
public final class JdbcPublishIntentRepository implements PublishIntentRepository {

    private final JdbcTemplate jdbc;
    private final Path authRepoPath;

    public JdbcPublishIntentRepository(JdbcTemplate jdbc, Path authRepoPath) {
        this.jdbc = jdbc;
        this.authRepoPath = authRepoPath;
    }

    private RowMapper<PublishIntent> mapper() {
        return (ResultSet rs, int n) -> new PublishIntent(
                rs.getLong("id"),
                rs.getString("ticket_no"),
                rs.getInt("review_round"),
                ObjectId.of(rs.getString("tree_hash")),
                ObjectId.of(rs.getString("base_commit")),
                rs.getString("target_ref"),
                rs.getString("commit_message"),
                new CommitIdentity(rs.getString("author_name"), rs.getString("author_email"), rs.getString("author_date")),
                new CommitIdentity(rs.getString("committer_name"), rs.getString("committer_email"),
                        rs.getString("committer_date")),
                ApprovalId.of(rs.getString("approval_id")),
                rs.getString("commit_sha") == null ? null : ObjectId.of(rs.getString("commit_sha")),
                PublishStatus.valueOf(rs.getString("status")),
                rs.getString("observed_ref_before"),
                rs.getString("observed_ref_after"),
                Instant.parse(rs.getString("created_at")),
                rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")),
                RepoRef.of(Path.of(rs.getString("clone_path"))),
                RepoRef.of(authRepoPath));
    }

    private static final String SELECT = """
            SELECT pi.*, t.clone_path AS clone_path
              FROM publish_intent pi
              JOIN ticket t ON t.ticket_no = pi.ticket_no
            """;

    @Override
    public PublishIntent insertPending(
            String ticketNo,
            int reviewRound,
            Snapshot snapshot,
            String commitMessage,
            CommitIdentity author,
            CommitIdentity committer,
            ApprovalId approvalId,
            Instant now,
            RepoRef cloneRepo,
            RepoRef authRepo) {
        jdbc.update("""
                INSERT INTO publish_intent(ticket_no, review_round, tree_hash, base_commit, target_ref,
                                           commit_message,
                                           author_name, author_email, author_date,
                                           committer_name, committer_email, committer_date,
                                           approval_id, commit_sha, status,
                                           observed_ref_before, observed_ref_after,
                                           created_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,NULL,?,NULL)
                """,
                ticketNo, reviewRound, snapshot.treeHash().hex(), snapshot.baseCommit().hex(), snapshot.targetRef(),
                commitMessage,
                author.name(), author.email(), author.date(),
                committer.name(), committer.email(), committer.date(),
                approvalId.value(), PublishStatus.PENDING.name(),
                snapshot.baseCommit().hex(),
                now.toString());
        return find(ticketNo, reviewRound, snapshot.treeHash()).orElseThrow(
                () -> new IllegalStateException("publish_intent row vanished right after insert"));
    }

    @Override
    public Optional<PublishIntent> find(String ticketNo, int reviewRound, ObjectId treeHash) {
        List<PublishIntent> rows = jdbc.query(
                SELECT + " WHERE pi.ticket_no = ? AND pi.review_round = ? AND pi.tree_hash = ?",
                mapper(), ticketNo, reviewRound, treeHash.hex());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<PublishIntent> findById(long id) {
        List<PublishIntent> rows = jdbc.query(SELECT + " WHERE pi.id = ?", mapper(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void updateCommitSha(long id, ObjectId commitSha) {
        int updated = jdbc.update("UPDATE publish_intent SET commit_sha = ? WHERE id = ?", commitSha.hex(), id);
        if (updated != 1) {
            throw new IllegalStateException("no such publish_intent: " + id);
        }
    }

    @Override
    public void updateOutcome(long id, PublishStatus status, String observedBefore, String observedAfter,
                              Instant finishedAt) {
        int updated = jdbc.update("""
                UPDATE publish_intent
                   SET status = ?, observed_ref_before = ?, observed_ref_after = ?, finished_at = ?
                 WHERE id = ?
                """,
                status.name(), observedBefore, observedAfter, finishedAt == null ? null : finishedAt.toString(), id);
        if (updated != 1) {
            throw new IllegalStateException("no such publish_intent: " + id);
        }
    }

    @Override
    public List<PublishIntent> findPending() {
        return jdbc.query(SELECT + " WHERE pi.status = ? ORDER BY pi.id",
                mapper(), PublishStatus.PENDING.name());
    }

    @Override
    public List<PublishIntent> findByTicket(String ticketNo) {
        return jdbc.query(SELECT + " WHERE pi.ticket_no = ? ORDER BY pi.id", mapper(), ticketNo);
    }
}
