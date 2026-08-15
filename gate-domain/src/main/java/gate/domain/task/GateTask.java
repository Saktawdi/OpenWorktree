package gate.domain.task;

import java.time.Instant;

/**
 * Metadata for an asynchronous gate operation (review / publish / session-send).
 *
 * <p>Mirrors 执行文档-后端-web §4.3 — a single record for every long-running operation the Web
 * layer surfaces over SSE. The task is created {@code RUNNING} with no {@code finishedAt}, then
 * updated at most once more into a terminal state ({@code SUCCEEDED} carrying {@code resultJson},
 * or {@code FAILED} carrying {@code errorJson}).
 *
 * @param id         UUID, the idempotency key in the {@code gate_task} table.
 * @param type       {@code "review"} | {@code "publish"} | {@code "session-send"}.
 * @param ticketNo   owning ticket, or {@code null} when not ticket-bound (e.g. standalone ops).
 * @param sessionId  owning agent session, or {@code null} (only set for {@code session-send}).
 * @param status     {@link GateTaskStatus}.
 * @param startedAt  immutable creation instant (ISO-8601 in the DB).
 * @param finishedAt instant the task reached a terminal state, or {@code null} while {@code RUNNING}.
 * @param resultJson structured success result as JSON text, or {@code null}.
 * @param errorJson  structured failure detail as JSON text, or {@code null}.
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
        String errorJson) {

    /** True when {@code status} is {@code RUNNING}. */
    public boolean isRunning() {
        return status == GateTaskStatus.RUNNING;
    }

    /** True when {@code status} is {@code SUCCEEDED} or {@code FAILED} (a permanent terminal state). */
    public boolean isTerminal() {
        return status == GateTaskStatus.SUCCEEDED || status == GateTaskStatus.FAILED;
    }
}
