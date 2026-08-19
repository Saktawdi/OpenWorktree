package gate.ports;

import java.time.Instant;
import java.util.List;

/**
 * Task event storage and cursor protocol (production-architecture §8.3, ADR-004).
 * L3 design, L4 approval required before production use.
 * Storage owner: event (capability-registry), semantic owner by task/ticket/review etc.
 */
public interface TaskEventPort {

    record TaskEvent(String eventId, String taskId, long sequence, String eventType, String payloadJson, Instant createdAt, Instant expiresAt) {}

    /**
     * Append event within the same transaction as task state change.
     * Sequence is allocated atomically via locked task row or counter.
     */
    TaskEvent append(String taskId, String eventType, String payloadJson);

    /** Replay events with sequence > cursor, ordered ascending. */
    List<TaskEvent> replay(String taskId, long afterSequence);

    /** Returns latest sequence for task, or 0 if none. */
    long latestSequence(String taskId);
}
