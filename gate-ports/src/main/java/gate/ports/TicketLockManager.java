package gate.ports;

import java.util.Optional;

/**
 * Ticket-level serial lock (执行文档-后端-web §7.2): same {@code ticketNo} serializes
 * session sendMessage / presubmit / publish because they all operate on the same clone.
 *
 * <p>In-process ReentrantLock + cross-process FileLock, keyed by {@code ticketNo}. The blocking
 * {@link #acquire(String)} is used for short operations that may queue; {@link #tryAcquire(String)}
 * is used when the caller must fail fast instead of waiting (e.g. presubmit while a session is
 * running).
 */
public interface TicketLockManager {

    /** Blocks until the ticket lock is available. */
    AutoCloseable acquire(String ticketNo);

    /** Returns a held lock, or empty if another holder owns it right now. */
    Optional<AutoCloseable> tryAcquire(String ticketNo);
}
