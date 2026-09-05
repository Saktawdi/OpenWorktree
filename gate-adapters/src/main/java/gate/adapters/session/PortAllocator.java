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

    /** Lowest port of the configured range（启动期孤儿清扫的扫描下界）. */
    public int min() {
        return min;
    }

    /** Number of ports in the configured range（启动期孤儿清扫的扫描上界用）. */
    public int size() {
        return size;
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

    /**
     * 推进游标 n 格（不占用任何端口）。被外来进程占住的端口没有 release 语义，
     * 调用方（acquireUsablePort 的 2^n 跨步）用它跳过游标附近的死区。n=0 为合法的
     * 「跨 1 格」——allocate 本身已消耗当前格，无需再推。
     */
    public void skip(int n) {
        if (n > 0) {
            cursor.addAndGet(n);
        }
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
