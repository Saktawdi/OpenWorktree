package gate.adapters.health;

import gate.ports.KmsService;
import gate.ports.S3Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Health semantics per production-architecture §13.3.
 * /livez: process only; /readyz: DB writable + migrations + capacity; /status/dependencies: Git/S3/KMS.
 */
public final class HealthService {

    private final DataSource ds;
    private final JdbcTemplate jdbc;
    private final Path authRepo;
    private final S3Store s3;
    private final KmsService kms;
    private final gate.ports.RefObserver refObserver;

    public HealthService(DataSource ds, Path authRepo, S3Store s3, KmsService kms, gate.ports.RefObserver refObserver) {
        this.ds = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.authRepo = authRepo;
        this.s3 = s3;
        this.kms = kms;
        this.refObserver = refObserver;
    }

    public Map<String, Object> livez() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("now", Instant.now().toString());
        return m;
    }

    public Map<String, Object> readyz() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean dbWritable = checkDbWritable();
        boolean diskOk = checkDisk();
        boolean ok = dbWritable && diskOk;
        m.put("status", ok ? "ok" : "not_ready");
        m.put("db_writable", dbWritable);
        m.put("disk_ok", diskOk);
        m.put("now", Instant.now().toString());
        if (!ok) m.put("reason", !dbWritable ? "db not writable" : "disk full");
        return m;
    }

    public Map<String, Object> dependencies() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean authExists = Files.isDirectory(authRepo);
        m.put("auth_repo_exists", authExists);
        try {
            boolean tipOk = refObserver.tip(gate.domain.git.RepoRef.of(authRepo), "refs/heads/main").isPresent();
            // tip may be empty for empty repo, but repo exists is still ok; we report actual
            m.put("auth_ref_ok", tipOk);
            if (!tipOk) m.put("auth_ref_note", "no refs/heads/main yet");
        } catch (Exception e) { m.put("auth_ref_ok", false); m.put("auth_ref_error", e.getMessage()); }
        try {
            // S3 liveness: try put+head+delete probe, not just head of missing key
            String probeKey = "health/probe-" + Instant.now().toEpochMilli();
            byte[] probe = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String probeSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(probe));
            var put = s3.put(probeKey, probe, probeSha);
            var head = s3.head(probeKey);
            boolean s3Ok = head.isPresent() && head.get().size() == probe.length;
            m.put("s3_ok", s3Ok);
            if (s3Ok) s3.delete(probeKey, put.versionId());
            else m.put("s3_error", "probe head missing or size mismatch");
        } catch (Exception e) { m.put("s3_ok", false); m.put("s3_error", e.getMessage()); }
        try { m.put("kms_ok", kms.keyRing() != null && kms.keyRing().currentKeyId() != null); } catch (Exception e) { m.put("kms_ok", false); m.put("kms_error", e.getMessage()); }
        m.put("now", Instant.now().toString());
        return m;
    }

    private boolean checkDbWritable() {
        try { jdbc.queryForObject("SELECT 1", Integer.class); jdbc.update("CREATE TABLE IF NOT EXISTS health_probe(id INTEGER PRIMARY KEY, v TEXT)"); jdbc.update("INSERT OR REPLACE INTO health_probe(id,v) VALUES (1, datetime('now'))"); return true; }
        catch (Exception e) { return false; }
    }

    private boolean checkDisk() {
        try { long usable = Files.getFileStore(authRepo.getParent() != null ? authRepo.getParent() : Path.of(".")).getUsableSpace(); return usable > 50 * 1024 * 1024; }
        catch (Exception e) { return false; }
    }
}
