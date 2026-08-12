package gate.ports;

import gate.domain.blob.BlobRef;
import gate.domain.policy.Decision;
import gate.domain.review.EngineDescriptor;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for {@code review_result}.
 *
 * <p>The stored findings blob is what {@code publish} rehydrates in order to re-run
 * {@link gate.domain.policy.GatePolicy} in its own process. The DB is not treated as authority for
 * permission: it stores the evidence, and the policy re-derives the verdict every time.
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
            Instant createdAt) {
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

    Optional<ReviewResultRow> findLatestForPresubmit(long presubmitId);
}
