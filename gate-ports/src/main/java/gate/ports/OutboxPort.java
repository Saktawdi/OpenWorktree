package gate.ports;

import java.time.Instant;
import java.util.List;

/**
 * Transactional outbox (production-architecture §8.2).
 * Business state + outbox in same TX; relay is at-least-once, consumer idempotent.
 */
public interface OutboxPort {

    record OutboxEntry(String outboxId, String aggregateType, String aggregateId, String eventType, String payloadJson, Instant createdAt) {}

    /** Append in same TX as business mutation. */
    void append(String aggregateType, String aggregateId, String eventType, String payloadJson);

    /** Relay up to limit, using SKIP LOCKED / lease. Returns relayed count. Default uses current tenant. */
    int relay(int limit);

    /** Explicit tenant relay for system background tasks. */
    default int relay(int limit, String tenantId) {
        return relay(limit);
    }

    List<OutboxEntry> pending(int limit);
    default List<OutboxEntry> pending(int limit, String tenantId) {
        return pending(limit);
    }
}
