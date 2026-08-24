package gate.ports.store;
import gate.ports.infra.Clock;


import gate.domain.blob.BlobRef;
import gate.domain.policy.Decision;
import gate.domain.review.EngineDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@code review_result}.
 *
 * <p>The stored findings blob is what {@code publish} rehydrates in order to re-run
 * {@link gate.domain.policy.GatePolicy} in its own process. The DB is not treated as authority for
 * permission: it stores the evidence, and the policy re-derives the verdict every time.
 *
 * <p>P4 extends the row with cost telemetry columns (prompt/completion/total tokens, token source,
 * review/LLM wall-clock, diff bytes/lines). These are <b>bypass</b> data — a failure to record cost
 * must never block publish or alter the verdict (执行文档 §4 P4).
 */
public interface ReviewResultRepository {

    record ReviewResultRow(
            long id,
            long presubmitId,
            EngineDescriptor engine,
            Decision.Verdict verdict,
            String findingsBlobPath,
            boolean coveredOk,
            boolean degraded,
            String rawBlobPath,
            Instant createdAt,
            // P4 cost telemetry (all nullable — unavailable when the engine gives no data)
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            String tokenSource,
            Long reviewWallMs,
            Long llmWallMs,
            Long diffBytes,
            Long diffLines) {

        /** Backward-compatible constructor for callers that don't have cost data (legacy/P1/P2 paths). */
        public ReviewResultRow(long id, long presubmitId, EngineDescriptor engine,
                               Decision.Verdict verdict, String findingsBlobPath,
                               boolean coveredOk, boolean degraded, String rawBlobPath, Instant createdAt) {
            this(id, presubmitId, engine, verdict, findingsBlobPath, coveredOk, degraded, rawBlobPath,
                    createdAt, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Cost telemetry to persist alongside the review result (P4). All fields may be null when the
     * engine provides no cost data — the export/verdict commands mark the basis as 'degraded'.
     */
    record CostRecord(
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            String tokenSource,
            Long reviewWallMs,
            Long llmWallMs,
            Long diffBytes,
            Long diffLines) {

        /** An empty cost record (no telemetry at all — e.g. manual review). */
        public static final CostRecord EMPTY = new CostRecord(null, null, null, null, null, null, null, null);
    }

    ReviewResultRow insert(
            long presubmitId,
            EngineDescriptor engine,
            Decision.Verdict verdict,
            BlobRef findings,
            boolean coveredOk,
            boolean degraded,
            BlobRef raw,
            Instant now);

    /**
     * Inserts a review result with P4 cost telemetry. The cost data is bypass — a failure here must
     * not block the publish path (执行文档 §4 P4 hard constraint).
     */
    ReviewResultRow insert(
            long presubmitId,
            EngineDescriptor engine,
            Decision.Verdict verdict,
            BlobRef findings,
            boolean coveredOk,
            boolean degraded,
            BlobRef raw,
            Instant now,
            CostRecord cost);

    Optional<ReviewResultRow> findLatestForPresubmit(long presubmitId);

    /**
     * Returns all review results joined with their ticket and presubmit data, for the P4 metrics
     * export (执行文档 §4 P4, §15). Each row carries enough to compute the H1 metrics:
     * review_round, verdict, diff_bytes, diff_lines, token counts, wall-clock.
     */
    List<ReviewResultRow> findAllForMetrics();
}
