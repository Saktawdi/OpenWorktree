package gate.ports;

import gate.domain.task.GateTask;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persistent registry for asynchronous tasks surfaced over SSE (执行文档-后端-web §4.3).
 *
 * <p>A task is created {@code RUNNING} by {@link #register(String, String, String)} and later
 * driven to a terminal state via {@link #update(GateTask)}. Terminal states are permanent.
 *
 * <h2>Event stream semantics ({@link #stream(String)})</h2>
 *
 * <p>Every task exposes one ordered, non-repeating stream of {@link GateTaskEvent}s that
 * <em>consumers may subscribe to any number of times and concurrently</em>. Each subscription:
 * <ol>
 *   <li>replays every event already emitted for the {@code id}, in emission order;
 *   <li>then receives live events as they are published;
 *   <li>terminates (finite stream) as soon as the {@code done} event for a terminal state is
 *       received.
 * </ol>
 *
 * <p>Event {@code kind} convention, agreed by the Web SSE layer:
 * <ul>
 *   <li>{@code "progress"} — transient progress heartbeat; carries the task's {@code payloadJson}
 *       snapshot (all eight fields as ISO-8601 strings). May be emitted many times while
 *       {@code RUNNING}.</li>
 *   <li>{@code "done"} — the final, terminal event for the task ({@code SUCCEEDED} or
 *       {@code FAILED}). Emitted once; after it the stream for that {@code id} is closed and no
 *       further events are published.</li>
 * </ul>
 *
 * <p>The stream returned by {@link #stream(String)} is finite: it terminates of its own accord
 * once {@code done} is observed (or immediately if the task is already terminal). Implementations
 * must not block forever.
 */
public interface TaskRegistry {

    /** Creates a {@code RUNNING} task, persists it, and publishes the initial {@code progress} event. */
    GateTask register(String type, String ticketNo, String sessionId);

    /**
     * Persists the current snapshot and publishes an event:
     * {@code progress} while non-terminal, then a single final {@code done} for a terminal task
     * (after which that task's event stream is permanently closed).
     */
    void update(GateTask task);

    /** Looks a task up by its idempotent {@code id}. */
    Optional<GateTask> find(String id);

    /**
     * Returns a finite, already-started-then-live {@code Stream} of events for {@code id}, ending
     * after the {@code done} terminal event. See the interface javadoc for full semantics.
     */
    Stream<GateTaskEvent> stream(String id);

    /**
     * W3C SSE Last-Event-ID cursor variant: replay DB events with {@code sequence > afterSequence}
     * then attach live. Default delegates to {@link #stream(String)} for adapters that have not yet
     * wired cursor filtering; JDBC impl overrides to eliminate replay gap.
     */
    default Stream<GateTaskEvent> streamWithCursor(String id, long afterSequence) {
        return stream(id);
    }

    /** Count of tasks currently in one status ("RUNNING" for the runtime status endpoint, V5). */
    default long countByStatus(String status) {
        throw new UnsupportedOperationException("countByStatus is not supported");
    }

    /** One immutable element of a task's event stream. */
    record GateTaskEvent(String taskId, String kind, String payloadJson, Instant at) {}
}
