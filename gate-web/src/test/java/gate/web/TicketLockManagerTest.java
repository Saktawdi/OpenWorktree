package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.lock.FileChannelTicketLockManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** S4 ticket lock tests (执行文档-后端-web §9.4). */
class TicketLockManagerTest {

    @Test
    void same_ticket_serializes_and_different_tickets_parallel() throws Exception {
        Path root = Files.createTempDirectory("gate-ticket-lock-");
        try {
            FileChannelTicketLockManager locks = new FileChannelTicketLockManager(root.resolve("locks"));

            // Hold the lock from another thread to avoid same-thread reentrant FileLock overlap.
            java.util.concurrent.atomic.AtomicReference<AutoCloseable> held = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try {
                    held.set(locks.acquire("T-1"));
                    release.await();
                    held.get().close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            holder.start();
            while (held.get() == null) {
                Thread.sleep(10);
            }
            Optional<AutoCloseable> second = locks.tryAcquire("T-1");
            assertTrue(second.isEmpty(), "same ticket must be busy");
            release.countDown();
            holder.join(3000);

            // After release the same ticket can be acquired again.
            try (AutoCloseable again = locks.tryAcquire("T-1").orElseThrow()) {
                assertTrue(true);
            }

            // Different tickets do not contend.
            try (AutoCloseable a = locks.tryAcquire("T-1").orElseThrow();
                 AutoCloseable b = locks.tryAcquire("T-2").orElseThrow()) {
                assertTrue(true);
            }
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(root);
        }
    }

    @Test
    void blocking_acquire_waits_until_release() throws Exception {
        Path root = Files.createTempDirectory("gate-ticket-lock-wait-");
        try {
            FileChannelTicketLockManager locks = new FileChannelTicketLockManager(root.resolve("locks"));
            AutoCloseable held = locks.acquire("T-WAIT");
            AtomicBoolean acquired = new AtomicBoolean(false);
            Thread t = new Thread(() -> {
                try (AutoCloseable ignored = locks.acquire("T-WAIT")) {
                    acquired.set(true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
            Thread.sleep(100);
            assertFalse(acquired.get(), "second thread should be blocked");
            held.close();
            t.join(3000);
            assertTrue(acquired.get(), "second thread should acquire after release");
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(root);
        }
    }
}
