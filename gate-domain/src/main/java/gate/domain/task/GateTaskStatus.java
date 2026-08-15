package gate.domain.task;

/**
 * Lifecycle status of an asynchronous {@link GateTask}.
 *
 * <p>Executing document 执行文档-后端-web §4.3 defines exactly three states. A task begins
 * {@code RUNNING} when {@code register()} inserts it, and transitions to a terminal state
 * ({@code SUCCEEDED} or {@code FAILED}) exactly once when it finishes. Terminal states are
 * permanent — {@code update()} of a terminal task publishes no further events.
 */
public enum GateTaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
