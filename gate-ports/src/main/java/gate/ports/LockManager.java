package gate.ports;

/**
 * Per-{@code (project, targetRef)} serial lock (架构落地执行文档 §9.1).
 *
 * <p>Granularity matters: with independent clones the only genuinely contended resource is the
 * {@code auth.git} ref update (a check-then-act: verify {@code old == base}, then move). Locking
 * anything wider would serialise work that does not contend.
 *
 * <p>Order is fixed (R-LOCK, §9.3): in-process lock → {@code FileLock} → SQLite write transaction,
 * never inverted, and a SQLite transaction never spans a {@code ProcessBuilder} call.
 */
public interface LockManager {

    /** @throws gate.domain.error.GateBusyException if the lock is held; never spins */
    AutoCloseable acquire(String project, String targetRef);
}
