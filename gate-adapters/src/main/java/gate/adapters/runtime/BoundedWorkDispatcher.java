package gate.adapters.runtime;

import gate.ports.WorkDispatcher;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local bounded worker pool used by the Web driver.
 *
 * <p>The queue is finite and rejection is explicit. This prevents a burst of review/session
 * requests from turning into unbounded heap growth. A distributed deployment can replace this
 * adapter with a broker-backed dispatcher without changing the application layer.
 */
public final class BoundedWorkDispatcher implements WorkDispatcher {

    public static final int DEFAULT_WORKERS = 4;
    public static final int DEFAULT_QUEUE_CAPACITY = 64;

    private final ThreadPoolExecutor executor;

    public BoundedWorkDispatcher() {
        this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    public BoundedWorkDispatcher(int workers, int queueCapacity) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "gate-work-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public boolean trySubmit(Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        try {
            executor.execute(work);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    @Override
    public int activeCount() {
        return executor.getActiveCount();
    }

    @Override
    public int queuedCount() {
        return executor.getQueue().size();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
