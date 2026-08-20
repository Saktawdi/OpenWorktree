package gate.adapters.audit;

import gate.ports.KmsService;
import gate.ports.security.AuditArchivePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * WORM audit archive with KMS-signed checkpoints (Phase4 ADR-007/008).
 * Local: file-backed hash-chain + checkpoint table; S3 Object Lock in enterprise.
 * Checkpoint = KMS-signed root hash covering events since last checkpoint, stored DB + object store.
 */
public final class WormAuditArchive implements AuditArchivePort {

    private final Path auditPath;
    private final JdbcTemplate jdbc;
    private final KmsService kms;
    private final HashChainAuditLog hashChain;

    public WormAuditArchive(Path auditPath, JdbcTemplate jdbc, KmsService kms) {
        this.auditPath = auditPath.toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.kms = kms;
        this.hashChain = new HashChainAuditLog(auditPath);
    }

    @Override
    public String appendAndGetHead(String chainHashLine) {
        // Direct delegation to hash chain; caller already appended via AuditLog.
        // Return last hash
        List<String> lines = hashChain.readLines();
        if (lines.isEmpty()) return "0".repeat(64);
        String last = lines.get(lines.size()-1);
        int idx = last.indexOf("\"hash\":\"");
        if (idx < 0) return "0".repeat(64);
        int from = idx + 8;
        int to = last.indexOf('"', from);
        return to < 0 ? "0".repeat(64) : last.substring(from, to);
    }

    @Override
    public Checkpoint createCheckpoint(String kmsKeyId) {
        if (!hashChain.verifyChain()) throw new IllegalStateException("audit chain broken, cannot checkpoint");
        List<String> lines = hashChain.readLines();
        long count = lines.size();
        String root = count == 0 ? sha256("") : sha256(String.join("\n", lines));
        // Determine prev checkpoint hash
        String prevHash = "0".repeat(64);
        List<Checkpoint> existing = listCheckpoints();
        if (!existing.isEmpty()) prevHash = existing.get(existing.size()-1).rootHash();
        String checkpointId = UUID.randomUUID().toString();
        String canonical = "{\"checkpointId\":\"" + checkpointId + "\",\"prevHash\":\"" + prevHash + "\",\"rootHash\":\"" + root + "\",\"count\":" + count + "}";
        String kid = kmsKeyId == null ? kms.keyRing().currentKeyId() : kmsKeyId;
        String sig;
        if (kms instanceof gate.adapters.kms.LocalKmsService lms) sig = lms.rawSign(canonical, kid);
        else sig = kms.sign(canonical, kid).signature();
        Checkpoint cp = new Checkpoint(checkpointId, prevHash, root, kid, sig, Instant.now(), count);
        jdbc.update("INSERT INTO audit_checkpoint(checkpoint_id, prev_checkpoint_hash, root_hash, kms_key_id, signature, created_at, event_count) VALUES (?,?,?,?,?,?,?)",
                cp.checkpointId(), cp.prevCheckpointHash(), cp.rootHash(), cp.kmsKeyId(), cp.signature(), cp.createdAt().toString(), cp.eventCount());
        return cp;
    }

    @Override
    public boolean verifyChain() {
        if (!hashChain.verifyChain()) return false;
        List<Checkpoint> cps = listCheckpoints();
        for (Checkpoint cp : cps) {
            String canonical = "{\"checkpointId\":\"" + cp.checkpointId() + "\",\"prevHash\":\"" + cp.prevCheckpointHash() + "\",\"rootHash\":\"" + cp.rootHash() + "\",\"count\":" + cp.eventCount() + "}";
            boolean ok;
            if (kms instanceof gate.adapters.kms.LocalKmsService lms) ok = lms.rawVerify(canonical, cp.signature(), cp.kmsKeyId());
            else {
                KmsService.Signature sig = new KmsService.Signature(cp.kmsKeyId(), "HMAC-SHA256", cp.signature(), 0, Long.MAX_VALUE);
                ok = kms.verify(canonical, sig);
            }
            if (!ok) return false;
        }
        return true;
    }

    @Override
    public List<Checkpoint> listCheckpoints() {
        return jdbc.query("SELECT checkpoint_id, prev_checkpoint_hash, root_hash, kms_key_id, signature, created_at, event_count FROM audit_checkpoint ORDER BY created_at ASC",
                (rs,n) -> new Checkpoint(rs.getString("checkpoint_id"), rs.getString("prev_checkpoint_hash"), rs.getString("root_hash"), rs.getString("kms_key_id"), rs.getString("signature"), Instant.parse(rs.getString("created_at")), rs.getLong("event_count")));
    }

    @Override
    public Optional<Checkpoint> findCheckpoint(String checkpointId) {
        List<Checkpoint> l = jdbc.query("SELECT checkpoint_id, prev_checkpoint_hash, root_hash, kms_key_id, signature, created_at, event_count FROM audit_checkpoint WHERE checkpoint_id=?",
                (rs,n) -> new Checkpoint(rs.getString("checkpoint_id"), rs.getString("prev_checkpoint_hash"), rs.getString("root_hash"), rs.getString("kms_key_id"), rs.getString("signature"), Instant.parse(rs.getString("created_at")), rs.getLong("event_count")), checkpointId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    @Override
    public String detectTampering() {
        if (!hashChain.verifyChain()) return "hash chain broken";
        if (!verifyChain()) return "checkpoint signature invalid or chain broken";
        return null;
    }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
