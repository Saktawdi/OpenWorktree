package gate.adapters.store;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;

/**
 * Nonce consumption store for OID CAS (ADR-003). Nonce + ref update are атомic via same DB TX / git ref TX.
 * Team mode: refs/gate/authorizations/<nonce> ; local: DB table gate_nonce enforces uniqueness.
 */
public final class JdbcNonceStore implements gate.ports.NonceStore {
    private final JdbcTemplate jdbc;
    public JdbcNonceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean tryConsume(String nonce, String ticketNo, String targetRef, String newCommitOid, Instant now) {
        try {
            int inserted = jdbc.update("INSERT INTO gate_nonce(nonce, ticket_no, target_ref, new_commit_oid, consumed_at) VALUES (?,?,?,?,?)",
                    nonce, ticketNo, targetRef, newCommitOid, now.toString());
            return inserted == 1;
        } catch (Exception e) {
            // duplicate -> already consumed
            return false;
        }
    }

    @Override
    public boolean isConsumed(String nonce) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM gate_nonce WHERE nonce = ?", Long.class, nonce);
        return c != null && c > 0;
    }

    @Override
    public int gcBefore(Instant threshold) {
        return jdbc.update("DELETE FROM gate_nonce WHERE consumed_at <= ?", threshold.toString());
    }
}
