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

    /** Relay up to limit, using SKIP LOCKED / lease. Returns relayed count. */
    int relay(int limit);

    List<OutboxEntry> pending(int limit);
}
