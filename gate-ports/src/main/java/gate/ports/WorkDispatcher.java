package gate.ports;

/**
 * Bounded execution boundary for long-running application work.
 *
 * <p>The port deliberately exposes admission control instead of an unbounded {@code Executor} API.
 * A caller must handle a rejected submission as a normal, observable BUSY outcome.
 */
public interface WorkDispatcher extends AutoCloseable {

    /** Attempts to admit work without waiting indefinitely. */
    boolean trySubmit(Runnable work);

    int activeCount();

    int queuedCount();

    @Override
    void close();
}
