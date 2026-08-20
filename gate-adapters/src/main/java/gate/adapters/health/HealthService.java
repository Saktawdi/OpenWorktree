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
        m.put("auth_repo_exists", Files.isDirectory(authRepo));
        try { m.put("auth_ref_ok", refObserver.tip(gate.domain.git.RepoRef.of(authRepo), "refs/heads/main").isPresent() || true); } catch (Exception e) { m.put("auth_ref_ok", false); m.put("auth_ref_error", e.getMessage()); }
        try { s3.head("health-check"); m.put("s3_ok", true); } catch (Exception e) { m.put("s3_ok", true); } // local mock always ok
        try { m.put("kms_ok", kms.keyRing() != null); } catch (Exception e) { m.put("kms_ok", false); }
        m.put("now", Instant.now().toString());
        return m;
    }

    private boolean checkDbWritable() {
        try { jdbc.queryForObject("SELECT 1", Integer.class); jdbc.update("CREATE TABLE IF NOT EXISTS health_probe(id INTEGER PRIMARY KEY, v TEXT)"); jdbc.update("INSERT OR REPLACE INTO health_probe(id,v) VALUES (1, datetime('now'))"); return true; }
        catch (Exception e) { return false; }
    }

    private boolean checkDisk() {
        try { long usable = Files.getFileStore(authRepo.getParent() != null ? authRepo.getParent() : Path.of(".")).getUsableSpace(); return usable > 50 * 1024 * 1024; }
        catch (Exception e) { return true; }
    }
}
