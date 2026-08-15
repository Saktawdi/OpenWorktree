package gate.adapters.session;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency-safe port allocator for {@code opencode serve} (执行文档-后端-web §7.3).
 *
 * <p>An {@link AtomicInteger} cursor plus a {@link ConcurrentHashMap} occupancy set gives atomic
 * allocation with a bounded retry sweep. Ports are released explicitly when a session process exits.
 */
public final class PortAllocator {

    private final int min;
    private final int size;
    private final AtomicInteger cursor = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Boolean> used = new ConcurrentHashMap<>();

    public PortAllocator(int min, int max) {
        if (min <= 0 || max > 65535 || min > max) {
            throw new IllegalArgumentException("invalid port range: [" + min + ", " + max + "]");
        }
        this.min = min;
        this.size = max - min + 1;
    }

    /** Atomically reserves a free port in the configured range. */
    public int allocate() {
        for (int i = 0; i < size; i++) {
            int offset = Math.floorMod(cursor.getAndIncrement(), size);
            int port = min + offset;
            if (used.putIfAbsent(port, Boolean.TRUE) == null) {
                return port;
            }
        }
        throw new GateException(GateErrorCode.GATE_ERROR_IO,
                "no free opencode ports in range [" + min + ", " + (min + size - 1) + "]");
    }

    /** Releases a previously allocated port so it can be reused. */
    public void release(int port) {
        used.remove(port);
    }

    /** Number of currently allocated ports. */
    public int allocatedCount() {
        return used.size();
    }
}
