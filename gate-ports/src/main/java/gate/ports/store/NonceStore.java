package gate.ports.store;

import java.time.Instant;

/**
 * Nonce consumption for Git CAS (ADR-003). Bind to publish phase.
 */
public interface NonceStore {
    boolean tryConsume(String nonce, String ticketNo, String targetRef, String newCommitOid, Instant now);
    boolean isConsumed(String nonce);
    int gcBefore(Instant threshold);
}
