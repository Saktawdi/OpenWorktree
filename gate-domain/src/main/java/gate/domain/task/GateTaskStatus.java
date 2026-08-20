package gate.domain.task;

/**
 * Lifecycle status of an asynchronous {@link GateTask}.
 *
 * <p>Phase 3: full state machine per production-architecture §6.1.
 * Legacy 3-state view (RUNNING/SUCCEEDED/FAILED) is retained as the core,
 * extended with QUEUED/RETRY_WAIT/CANCEL_REQUESTED/CANCELLED for team
 * multi-worker and HA execution. Terminal states are permanent.
 */
public enum GateTaskStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    CANCEL_REQUESTED,
    CANCELLED,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
