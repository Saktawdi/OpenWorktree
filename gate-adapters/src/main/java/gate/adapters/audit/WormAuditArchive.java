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
    private final gate.ports.S3Store s3;
    private final HashChainAuditLog hashChain;

    public WormAuditArchive(Path auditPath, JdbcTemplate jdbc, KmsService kms) {
        this(auditPath, jdbc, kms, null);
    }

    public WormAuditArchive(Path auditPath, JdbcTemplate jdbc, KmsService kms, gate.ports.S3Store s3) {
        this.auditPath = auditPath.toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.kms = kms;
        this.s3 = s3;
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
        // WORM archive to S3 ObjectLock (enterprise) / FsS3Store versioning (local)
        if (s3 != null) {
            try {
                String prefixContent = count == 0 ? "" : String.join("\n", lines.subList(0, (int) Math.min(count, lines.size())));
                byte[] data = prefixContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // Use digest as key to ensure immutability, but also store under checkpoint id for retrieval
                String key = "audit/" + checkpointId + ".log";
                s3.put(key, data, sha256(prefixContent));
                // Also store with WORM retention tag (simulated via S3 metadata, local mock keeps version)
            } catch (Exception e) {
                // Fail checkpoint if S3 archive fails (WORM must be durable)
                throw new RuntimeException("WORM S3 archive failed", e);
            }
        }
        return cp;
    }

    @Override
    public boolean verifyChain() {
        if (!hashChain.verifyChain()) return false;
        List<String> lines = hashChain.readLines();
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
            // Verify checkpoint root matches log prefix hash (detects truncation or prefix tampering)
            long count = cp.eventCount();
            String expectedRoot;
            if (count == 0) expectedRoot = sha256("");
            else if (count > lines.size()) return false; // checkpoint claims more events than log has -> missing
            else expectedRoot = sha256(String.join("\n", lines.subList(0, (int) count)));
            if (!expectedRoot.equals(cp.rootHash())) return false;
            // Verify S3 WORM archive exists and matches root (if S3 configured)
            if (s3 != null) {
                try {
                    var head = s3.head("audit/" + cp.checkpointId() + ".log");
                    if (head.isEmpty()) return false;
                    byte[] data = s3.get("audit/" + cp.checkpointId() + ".log");
                    String s3Root = sha256(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                    if (!s3Root.equals(cp.rootHash())) return false;
                } catch (Exception e) { return false; }
            }
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
