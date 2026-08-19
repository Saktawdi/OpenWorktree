package gate.domain.task;

import java.time.Instant;

/**
 * Metadata for an asynchronous gate operation (review / publish / session-send).
 *
 * <p>Phase 2 & ADR-002: Extended with tenancy, idempotency, lease, attempt, fencing,
 * and sequence tracking for multi-worker and HA execution.
 */
public record GateTask(
        String id,
        String type,
        String ticketNo,
        String sessionId,
        GateTaskStatus status,
        Instant startedAt,
        Instant finishedAt,
        String resultJson,
        String errorJson,
        String tenantId,
        String projectId,
        String idempotencyKey,
        String requestDigest,
        int priority,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        int attempt,
        int maxAttempts,
        long fenceToken,
        long nextEventSequence,
        Instant timeoutAt,
        Instant cancelRequestedAt,
        String resultRef,
        String errorCode) {

    public GateTask(
            String id,
            String type,
            String ticketNo,
            String sessionId,
            GateTaskStatus status,
            Instant startedAt,
            Instant finishedAt,
            String resultJson,
            String errorJson) {
        this(
                id,
                type,
                ticketNo,
                sessionId,
                status,
                startedAt,
                finishedAt,
                resultJson,
                errorJson,
                "default",
                null,
                null,
                null,
                0,
                startedAt,
                null,
                null,
                0,
                3,
                0L,
                0L,
                null,
                null,
                null,
                null);
    }

    /** True when {@code status} is {@code RUNNING}. */
    public boolean isRunning() {
        return status == GateTaskStatus.RUNNING;
    }

    /** True when {@code status} is {@code SUCCEEDED} or {@code FAILED} (a permanent terminal state). */
    public boolean isTerminal() {
        return status == GateTaskStatus.SUCCEEDED || status == GateTaskStatus.FAILED;
    }
}
