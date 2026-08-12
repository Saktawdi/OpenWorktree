package gate.adapters.store;

import gate.domain.blob.BlobRef;
import gate.domain.policy.Decision;
import gate.domain.review.EngineDescriptor;
import gate.ports.ReviewResultRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-backed {@code review_result} store.
 *
 * <p>{@code provider_id} is NOT NULL by DDL and references {@code provider(id)}. P1's human verdict
 * satisfies it via the seeded {@code manual} provider row rather than by relaxing the constraint —
 * a manual review is simply a degenerate engine, which is exactly what the "adding a second engine
 * costs zero core changes" design promised.
 */
public final class JdbcReviewResultRepository implements ReviewResultRepository {

    private final JdbcTemplate jdbc;

    public JdbcReviewResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReviewResultRow> MAPPER = (ResultSet rs, int n) -> new ReviewResultRow(
            rs.getLong("id"),
            rs.getLong("presubmit_id"),
            new EngineDescriptor(
                    rs.getString("engine_id"),
                    rs.getString("engine_version"),
                    "",
                    rs.getString("provider_id"),
                    rs.getString("model_name")),
            Decision.Verdict.valueOf(rs.getString("verdict")),
            rs.getString("findings_blob"),
            rs.getInt("covered_ok") == 1,
            rs.getInt("degraded") == 1,
            rs.getString("raw_blob"),
            Instant.parse(rs.getString("created_at")));

    @Override
    public ReviewResultRow insert(
            long presubmitId,
            EngineDescriptor engine,
            Decision.Verdict verdict,
            BlobRef findings,
            boolean coveredOk,
            boolean degraded,
            BlobRef raw,
            Instant now) {
        jdbc.update("""
                INSERT INTO review_result(presubmit_id, engine_id, engine_version, provider_id, model_name,
                                          verdict, findings_blob, covered_ok, degraded, raw_blob, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                presubmitId, engine.engineId(), engine.engineVersion(), engine.providerId(), engine.modelName(),
                verdict.name(), findings.relPath(), coveredOk ? 1 : 0, degraded ? 1 : 0, raw.relPath(),
                now.toString());
        return findLatestForPresubmit(presubmitId).orElseThrow(
                () -> new IllegalStateException("review_result row vanished right after insert"));
    }

    @Override
    public Optional<ReviewResultRow> findLatestForPresubmit(long presubmitId) {
        List<ReviewResultRow> rows = jdbc.query(
                "SELECT * FROM review_result WHERE presubmit_id = ? ORDER BY id DESC LIMIT 1", MAPPER, presubmitId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
