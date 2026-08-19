package gate.adapters.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedWorkDispatcherTest {

    @Test
    void rejectsWorkWhenWorkersAndQueueAreFull() throws Exception {
        try (BoundedWorkDispatcher dispatcher = new BoundedWorkDispatcher(1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            assertTrue(dispatcher.trySubmit(() -> {
                started.countDown();
                await(release);
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(dispatcher.trySubmit(() -> await(release)));
            assertFalse(dispatcher.trySubmit(() -> { }));
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
