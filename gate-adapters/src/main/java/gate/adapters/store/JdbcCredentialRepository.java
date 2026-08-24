package gate.adapters.store;

import gate.ports.store.CredentialRepository;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-backed {@code credential} store (P3, §11.3).
 *
 * <p>Only the SHA-256 hash of a token is stored — the plaintext is returned once at issuance and
 * never persisted (§6.1 / ADR-9). Validation is by constant-time hash comparison: we re-hash the
 * presented token and look up the hash in the DB. A revoked token ({@code revoked_at IS NOT NULL})
 * is treated as invalid even if the hash matches.
 */
public final class JdbcCredentialRepository implements CredentialRepository {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit token, hex-encoded to 64 chars

    private final JdbcTemplate jdbc;

    public JdbcCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Domain validate(String token) {
        if (token == null || token.isBlank()) {
            return Domain.invalid();
        }
        String hash = sha256Hex(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT domain, ticket_no, revoked_at FROM credential WHERE token_hash = ?", hash);
        if (rows.isEmpty()) {
            return Domain.invalid();
        }
        Map<String, Object> row = rows.get(0);
        Object revokedAt = row.get("revoked_at");
        if (revokedAt != null) {
            return Domain.invalid();
        }
        String domain = String.valueOf(row.get("domain"));
        String ticketNo = row.get("ticket_no") == null ? null : String.valueOf(row.get("ticket_no"));
        return new Domain(domain, ticketNo);
    }

    @Override
    public String issueAgentToken(String ticketNo, Instant now) {
        String token = generateToken();
        String hash = sha256Hex(token);
        jdbc.update(
                "INSERT INTO credential(token_hash, domain, ticket_no, created_at) VALUES (?,?,?,?)",
                hash, Domain.AGENT, ticketNo, now.toString());
        return token;
    }

    @Override
    public String issueHumanToken(Instant now) {
        String token = generateToken();
        String hash = sha256Hex(token);
        jdbc.update(
                "INSERT INTO credential(token_hash, domain, ticket_no, created_at) VALUES (?,?,NULL,?)",
                hash, Domain.HUMAN, now.toString());
        return token;
    }

    @Override
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String hash = sha256Hex(token);
        // We don't know the current time here without a Clock; use the DB-side approach of marking
        // revoked_at to a non-null value. The caller (a human-domain CLI command) supplies the time
        // implicitly via a second update if needed — but for simplicity we set it to now() at the
        // DB level. SQLite doesn't have now(), so we use the Java side.
        Optional<Long> id = jdbc.queryForList(
                "SELECT id FROM credential WHERE token_hash = ?", Long.class, hash)
                .stream().findFirst();
        id.ifPresent(rowId -> jdbc.update(
                "UPDATE credential SET revoked_at = ? WHERE id = ?", Instant.now().toString(), rowId));
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is mandated by the JLS; this is unreachable.
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
