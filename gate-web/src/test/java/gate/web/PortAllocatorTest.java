package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gate.adapters.session.PortAllocator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** S3/S4 PortAllocator concurrency tests (执行文档-后端-web §9.4). */
class PortAllocatorTest {

    @Test
    void concurrent_allocate_returns_unique_ports_and_release_reuses() throws Exception {
        PortAllocator allocator = new PortAllocator(50000, 50009);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(allocator::allocate);
            }
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            Set<Integer> ports = new HashSet<>();
            for (Future<Integer> f : futures) {
                ports.add(f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(10, ports.size(), "all allocated ports must be distinct");

            // Release one and allocate again: the same port can come back.
            int first = ports.iterator().next();
            allocator.release(first);
            int again = allocator.allocate();
            assertNotEquals(-1, again);
            assertEquals(10, allocator.allocatedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void allocate_throws_when_range_exhausted() {
        PortAllocator allocator = new PortAllocator(50100, 50101);
        allocator.allocate();
        allocator.allocate();
        assertThrows(RuntimeException.class, allocator::allocate);
    }
}
