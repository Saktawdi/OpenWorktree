package gate.ports;

import gate.domain.task.GateTask;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Team/enterprise task claim and lease protocol (ADR-002, production-architecture §6.3-6.4).
 * Local SQLite implements the same contract without SKIP LOCKED; PG uses atomic CTE.
 */
public interface TaskClaimPort {

    /** Enqueue a task in QUEUED state (production path, distinct from legacy register RUNNING). */
    GateTask enqueue(String type, String ticketNo, String sessionId, String tenantId, String projectId,
                     String idempotencyKey, String requestDigest, int priority, Instant availableAt);

    /**
     * Atomically claim the highest-priority QUEUED/RETRY_WAIT task whose available_at <= now and
     * lease has expired. PG must use FOR UPDATE SKIP LOCKED + UPDATE ... RETURNING in one TX.
     * Returns empty when nothing claimable.
     */
    Optional<GateTask> claimNext(String workerId, Duration lease, Instant now);

    /** Renew lease for an owned RUNNING task. Must match id+lease_owner+fence_token. */
    boolean renewLease(String taskId, String workerId, long fenceToken, Duration lease, Instant now);

    /** Reaper: move expired RUNNING leases to RETRY_WAIT or FAILED (single writer). Returns reaped count. */
    int reapExpiredLeases(Instant now);

    /** Heartbeat probe: count stale writes etc. for metrics. */
    long countStaleRejections();
}
