package gate.domain.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in the hash-chained, append-only audit log (架构落地执行文档 §10.2).
 *
 * <p>The chain is the detection half of the threat model (§1.3): a same-privilege agent can edit
 * the file, but it cannot do so without breaking {@code prev_hash} continuity.
 *
 * @param fields flat, JSON-serialisable payload. Must never contain secrets — no API keys, no
 *               {@code base_url}; provider references are ids only (ADR-9).
 */
public record AuditEvent(Instant at, String kind, String ticketNo, Integer reviewRound, Map<String, String> fields) {

    public AuditEvent {
        if (at == null) {
            throw new IllegalArgumentException("at must not be null");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        fields = Map.copyOf(fields);
    }

    public static AuditEvent of(Instant at, String kind, String ticketNo, Integer round, Map<String, String> fields) {
        return new AuditEvent(at, kind, ticketNo, round, fields);
    }
}
