package gate.ports;

import java.time.Instant;
import java.util.List;

/**
 * Task event storage and cursor protocol (production-architecture §8.3, ADR-004).
 * L3 design, L4 approval required before production use.
 * Storage owner: event (capability-registry), semantic owner by task/ticket/review etc.
 *
 * <h2>Backpressure / 背压 contract (production-architecture §9.3, Phase 2 exit #4, DEBT-006)</h2>
 * <p>背压语义：SSE 慢消费者不会造成无界内存增长。Server-side per-connection memory is bounded to
 * {@code SseHandler.MAX_BUFFERED_EVENTS = 100} events via a fixed-capacity queue
 * ({@code ArrayBlockingQueue}). If the buffer fills, the oldest pending event is
 * discarded (drop-oldest) and a backpressure counter is retained. No unbounded queue
 * is ever created. The authoritative backlog is always persistent: {@code task_event}
 * table keyed by {@code (task_id, sequence)}.</p>
 * <p>Clients recover via W3C SSE {@code Last-Event-ID} cursor:
 * {@link #replay(String, long)} returns {@code sequence > cursor} ordered ascending,
 * so any dropped in-memory events are still replayable from the DB after a
 * disconnect. Heartbeats ({@code : ping}) are not persisted and never occupy a
 * sequence. Cursor validation: {@code cursor < earliest retained} →
 * {@code EVENT_CURSOR_EXPIRED}; {@code cursor > latest} → {@code INVALID_EVENT_CURSOR}.</p>
 * <p>Implementations MUST NOT grow per-subscriber buffers without bound and MUST keep
 * DB as the source of truth for replay, not the ephemeral in-memory channel.</p>
 */
public interface TaskEventPort {

    record TaskEvent(String eventId, String taskId, long sequence, String eventType, String payloadJson, Instant createdAt, Instant expiresAt) {}

    /**
     * Append event within the same transaction as task state change.
     * Sequence is allocated atomically via locked task row or counter.
     */
    TaskEvent append(String taskId, String eventType, String payloadJson);

    /**
     * Replay events with sequence &gt; cursor, ordered ascending.
     * Persistent, DB-backed source for SSE {@code Last-Event-ID} recovery; remains
     * available even when an SSE connection dropped in-memory buffered events due to
     * {@code MAX_BUFFERED_EVENTS} backpressure (slow-consumer drop-oldest).
     * Heartbeats are never persisted and do not appear in replay.
     */
    List<TaskEvent> replay(String taskId, long afterSequence);

    /** Returns latest sequence for task, or 0 if none. */
    long latestSequence(String taskId);
}
