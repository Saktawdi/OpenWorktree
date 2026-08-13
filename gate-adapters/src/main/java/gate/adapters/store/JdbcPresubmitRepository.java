package gate.adapters.store;

import gate.domain.blob.BlobRef;
import gate.domain.git.ObjectId;
import gate.domain.snapshot.Snapshot;
import gate.ports.PresubmitRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-backed {@code presubmit} store.
 *
 * <p>{@link #nextRound} is a pure read. The round is only materialised by {@link #insert}, which the
 * application calls <em>after</em> every validation has passed — that is how an empty diff or a
 * blocked capture avoids consuming a review round (§3.2/§7.3).
 */
public final class JdbcPresubmitRepository implements PresubmitRepository {

    private final JdbcTemplate jdbc;

    public JdbcPresubmitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PresubmitRow> MAPPER = (ResultSet rs, int n) -> new PresubmitRow(
            rs.getLong("id"),
            rs.getString("ticket_no"),
            rs.getInt("review_round"),
            ObjectId.of(rs.getString("tree_hash")),
            ObjectId.of(rs.getString("base_commit")),
            rs.getString("target_ref"),
            rs.getString("diff_blob"),
            rs.getLong("diff_bytes"),
            rs.getString("diff_sha256"),
            Instant.parse(rs.getString("created_at")));

    @Override
    public int nextRound(String ticketNo) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(review_round), 0) FROM presubmit WHERE ticket_no = ?", Integer.class, ticketNo);
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public PresubmitRow insert(String ticketNo, int round, Snapshot snapshot, BlobRef diff, Instant now) {
        jdbc.update("""
                INSERT INTO presubmit(ticket_no, review_round, tree_hash, base_commit, target_ref,
                                      diff_blob, diff_bytes, diff_sha256, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                ticketNo, round, snapshot.treeHash().hex(), snapshot.baseCommit().hex(), snapshot.targetRef(),
                diff.relPath(), diff.bytes(), diff.sha256(), now.toString());
        return find(ticketNo, round).orElseThrow(
                () -> new IllegalStateException("presubmit row vanished right after insert: " + ticketNo + "/" + round));
    }

    @Override
    public Optional<PresubmitRow> find(String ticketNo, int round) {
        List<PresubmitRow> rows = jdbc.query(
                "SELECT * FROM presubmit WHERE ticket_no = ? AND review_round = ?", MAPPER, ticketNo, round);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<PresubmitRow> findLatest(String ticketNo) {
        List<PresubmitRow> rows = jdbc.query(
                "SELECT * FROM presubmit WHERE ticket_no = ? ORDER BY review_round DESC LIMIT 1", MAPPER, ticketNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<PresubmitRow> findAllByTicket(String ticketNo) {
        return jdbc.query(
                "SELECT * FROM presubmit WHERE ticket_no = ? ORDER BY review_round ASC", MAPPER, ticketNo);
    }

    @Override
    public Optional<PresubmitRow> findById(long id) {
        List<PresubmitRow> rows = jdbc.query(
                "SELECT * FROM presubmit WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
