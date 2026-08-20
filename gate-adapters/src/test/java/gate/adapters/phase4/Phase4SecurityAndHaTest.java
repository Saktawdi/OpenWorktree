package gate.adapters.phase4;

import gate.adapters.audit.HashChainAuditLog;
import gate.adapters.audit.WormAuditArchive;
import gate.adapters.backup.BackupService;
import gate.adapters.health.HealthService;
import gate.adapters.kms.LocalKmsService;
import gate.adapters.metrics.InMemoryMetrics;
import gate.adapters.metrics.NoopTracing;
import gate.adapters.s3.FsS3Store;
import gate.adapters.security.JdbcRbacStore;
import gate.adapters.security.TenantIsolationService;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.domain.audit.AuditEvent;
import gate.domain.security.Permission;
import gate.domain.security.RbacRole;
import gate.domain.security.SecurityContext;
import gate.ports.security.RbacPort;
import gate.application.metrics.SloService;
import gate.application.security.RbacService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase4 HA & Security verification (§17.3 exit, §13-15).
 * Covers: RBAC/SoD/tenant isolation, WORM audit, backup/restore, health/SLO, metrics.
 */
class Phase4SecurityAndHaTest {

    private Path tempDir;
    private DataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("gate-phase4-");
        Path db = tempDir.resolve("gate.db");
        ds = SqliteDataSourceFactory.create(db);
        SqliteDataSourceFactory.migrate(ds);
        jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null) Files.walk(tempDir).sorted((a,b)->b.compareTo(a)).forEach(p->{ try{Files.deleteIfExists(p);}catch(Exception ignored){}});
    }

    @Test
    void testRbacAndSoD() {
        // Seed RBAC
        JdbcRbacStore store = new JdbcRbacStore(jdbc);
        store.assignRole("alice", "default", "proj-1", RbacRole.REVIEWER, "admin");
        store.assignRole("alice", "default", "proj-1", RbacRole.PUBLISHER, "admin");
        Set<RbacRole> roles = store.resolveRoles("alice", "default", "proj-1");
        assertTrue(roles.contains(RbacRole.REVIEWER));
        assertTrue(roles.contains(RbacRole.PUBLISHER));

        SecurityContext ctx = new SecurityContext("alice", "default", "proj-1", roles, "hash");
        RbacService rbac = new RbacService(store);
        // Must allow review and publish
        assertDoesNotThrow(() -> rbac.require(ctx, Permission.REVIEW_RUN, "default", "proj-1"));
        assertDoesNotThrow(() -> rbac.require(ctx, Permission.PUBLISH_RUN, "default", "proj-1"));

        // SoD violation: same user reviewed then tries publish without exception
        String violation = gate.domain.security.SoDPolicy.check("alice", roles, "T-1", 1, true, false);
        assertNotNull(violation);
        assertTrue(violation.contains("SoD violation"));

        // With exception approved, allowed
        String ok = gate.domain.security.SoDPolicy.check("alice", roles, "T-1", 1, true, true);
        assertNull(ok);

        // Revoke publisher, then publish should deny
        store.revokeRole("alice", "default", "proj-1", RbacRole.PUBLISHER, "admin");
        Set<RbacRole> after = store.resolveRoles("alice", "default", "proj-1");
        assertFalse(after.contains(RbacRole.PUBLISHER));
        SecurityContext ctx2 = new SecurityContext("alice", "default", "proj-1", after, "hash");
        assertThrows(Exception.class, () -> rbac.require(ctx2, Permission.PUBLISH_RUN, "default", "proj-1"));

        // Cross-tenant isolation
        TenantIsolationService tenant = new TenantIsolationService(jdbc);
        assertThrows(Exception.class, () -> tenant.requireTenantAccess(ctx2, "other-tenant"));
        assertDoesNotThrow(() -> tenant.requireTenantAccess(ctx2, "default"));

        // SoD with reviewer_user_id and sod_exception (new overload)
        String reviewer = "alice";
        String publisher = "alice";
        // No exception -> violation when publisher == reviewer
        String v1 = gate.domain.security.SoDPolicy.check(publisher, Set.of(RbacRole.PUBLISHER), "T-1", 1, reviewer, false);
        assertNotNull(v1);
        // With exception -> allowed
        String v2 = gate.domain.security.SoDPolicy.check(publisher, Set.of(RbacRole.PUBLISHER), "T-1", 1, reviewer, true);
        assertNull(v2);
        // Different reviewer -> allowed
        String v3 = gate.domain.security.SoDPolicy.check("bob", Set.of(RbacRole.PUBLISHER), "T-1", 1, reviewer, false);
        assertNull(v3);

        // Real DB sod_exception flow: insert review_result with reviewer, then check exception table
        // Clean previous ticket if exists
        try { jdbc.update("DELETE FROM review_result WHERE presubmit_id IN (SELECT id FROM presubmit WHERE ticket_no='T-SOD')"); } catch (Exception ignored) {}
        try { jdbc.update("DELETE FROM presubmit WHERE ticket_no='T-SOD'"); } catch (Exception ignored) {}
        try { jdbc.update("DELETE FROM ticket WHERE ticket_no='T-SOD'"); } catch (Exception ignored) {}
        jdbc.update("INSERT INTO ticket(ticket_no,title,target_ref,clone_path,stage,created_at,updated_at,tenant_id) VALUES (?,?,?,?,?,?,?,?)",
                "T-SOD","sod","refs/heads/main",tempDir.resolve("cloneSod").toString(),"PRESUBMITTED", Instant.now().toString(), Instant.now().toString(), "default");
        // need provider for review_result FK
        try { jdbc.update("INSERT OR IGNORE INTO provider(id,name,base_url,api_key_ref,type,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                "manual","manual","local://manual","none","manual",Instant.now().toString(),Instant.now().toString()); } catch (Exception ignored) {}
        jdbc.update("INSERT INTO presubmit(ticket_no,review_round,tree_hash,base_commit,target_ref,diff_blob,diff_bytes,diff_sha256,created_at,tenant_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "T-SOD",1,"abc123","def456","refs/heads/main","blob",10,"sha",Instant.now().toString(),"default");
        Long presubmitId = jdbc.queryForObject("SELECT id FROM presubmit WHERE ticket_no='T-SOD'", Long.class);
        // Insert review_result with reviewer alice
        jdbc.update("INSERT INTO review_result(presubmit_id,engine_id,engine_version,provider_id,model_name,verdict,findings_blob,covered_ok,degraded,raw_blob,created_at,reviewer_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                presubmitId,"manual","1.0","manual","m","PASS","blob",1,0,"raw",Instant.now().toString(),"alice");
        String dbReviewer = jdbc.queryForObject("SELECT reviewer_user_id FROM review_result WHERE presubmit_id=? ORDER BY id DESC LIMIT 1", String.class, presubmitId);
        assertEquals("alice", dbReviewer);
        // Without sod_exception, alice publish should be blocked
        String v4 = gate.domain.security.SoDPolicy.check("alice", Set.of(RbacRole.PUBLISHER), "T-SOD", 1, dbReviewer, false);
        assertNotNull(v4);
        // Insert sod_exception and verify allowed
        String excId = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sod_exception(id,ticket_no,review_round,requester_user_id,approver_user_id,reason,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?)",
                excId,"T-SOD",1,"alice","admin","dual approval", Instant.now().plusSeconds(3600).toString(), Instant.now().toString());
        boolean hasExc = !jdbc.queryForList("SELECT 1 FROM sod_exception WHERE ticket_no='T-SOD' AND review_round=1 AND requester_user_id='alice' AND expires_at > datetime('now')").isEmpty();
        assertTrue(hasExc);
        String v5 = gate.domain.security.SoDPolicy.check("alice", Set.of(RbacRole.PUBLISHER), "T-SOD", 1, dbReviewer, hasExc);
        assertNull(v5);
    }

    @Test
    void testAlertsFiring() {
        var metrics = new InMemoryMetrics();
        var alertSvc = new gate.adapters.metrics.AlertService(metrics, jdbc);
        // Initially no firing
        var initial = alertSvc.firing();
        assertTrue(initial.isEmpty(), "no alerts initially");
        // Simulate stale fence spike: increment metric above threshold 10
        for (int i=0;i<12;i++) metrics.counter("gate_task_stale_rejections_total", 1, Map.of());
        var firing = alertSvc.firing();
        boolean hasStale = firing.stream().anyMatch(a -> a.ruleId().equals("ALERT_STALE_FENCE") && a.firing());
        assertTrue(hasStale, "stale fence alert should fire");
        // Also test spy: metrics counter for alert firing
        assertTrue(metrics.getCounter("gate_alert_firing_total") >= 1);
        // Lease expiry alert
        for (int i=0;i<6;i++) metrics.counter("gate_task_lease_expiry_total", 1, Map.of());
        var firing2 = alertSvc.firing();
        assertTrue(firing2.stream().anyMatch(a -> a.ruleId().equals("ALERT_LEASE_EXPIRY")));
    }

    @Test
    void testWormAuditArchive() throws Exception {
        Path auditPath = tempDir.resolve("audit.log");
        var chain = new HashChainAuditLog(auditPath);
        chain.append(AuditEvent.of(Instant.parse("2026-08-20T10:00:00Z"), "publish.done", "T-1", 1, Map.of("commit","abc")));
        chain.append(AuditEvent.of(Instant.parse("2026-08-20T10:01:00Z"), "publish.done", "T-2", 1, Map.of("commit","def")));
        assertTrue(chain.verifyChain());

        LocalKmsService kms = new LocalKmsService("k1", "secret1");
        var s3 = new gate.adapters.s3.FsS3Store(tempDir.resolve("s3worm"));
        WormAuditArchive archive = new WormAuditArchive(auditPath, jdbc, kms, s3);
        assertNull(archive.detectTampering());
        var cp = archive.createCheckpoint("k1");
        assertNotNull(cp);
        assertEquals(2, cp.eventCount());
        assertTrue(archive.verifyChain());
        assertEquals(1, archive.listCheckpoints().size());
        // WORM S3 archive must exist and match root
        assertTrue(s3.head("audit/" + cp.checkpointId() + ".log").isPresent());
        byte[] s3Data = s3.get("audit/" + cp.checkpointId() + ".log");
        String s3Content = new String(s3Data, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(s3Content.contains("T-1") && s3Content.contains("T-2"));

        // Tamper detection: corrupt file (append)
        Files.writeString(auditPath, "corrupted\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertNotNull(archive.detectTampering());
        assertFalse(archive.verifyChain());

        // Truncation detection: checkpoint claims 2 events but log now has corrupted extra, root mismatch
        // Create new archive instance to verify S3 still holds correct prefix
        var archive2 = new WormAuditArchive(auditPath, jdbc, kms, s3);
        assertFalse(archive2.verifyChain());
        // S3 archived prefix remains intact
        assertTrue(s3.head("audit/" + cp.checkpointId() + ".log").isPresent());
    }

    @Test
    void testBackupAndRestore() throws Exception {
        Path authRepo = tempDir.resolve("auth.git");
        Path gateHome = tempDir.resolve("gateHome");
        Files.createDirectories(gateHome);
        // init bare auth repo with a commit (so bundle is non-empty)
        var runner = new gate.adapters.process.ProcessRunnerImpl(tempDir);
        runner.run(List.of("git", "init", "--bare", authRepo.toString()), null, Map.of(), java.time.Duration.ofSeconds(5));
        // seed DB with ticket and verify backup will capture it
        jdbc.update("INSERT INTO ticket(ticket_no,title,target_ref,clone_path,stage,created_at,updated_at,tenant_id) VALUES (?,?,?,?,?,?,?,?)",
                "T-BK-1","bk","refs/heads/main",tempDir.resolve("clone").toString(),"DONE", Instant.now().toString(), Instant.now().toString(), "default");
        // Create a commit in auth repo so bundle has content
        Path seed = tempDir.resolve("seedBk");
        runner.run(List.of("git", "clone", authRepo.toString(), seed.toString()), null, Map.of(), java.time.Duration.ofSeconds(5));
        Files.writeString(seed.resolve("README.md"), "bk");
        runner.run(List.of("git", "-C", seed.toString(), "config", "user.name", "gate"), null, Map.of(), java.time.Duration.ofSeconds(5));
        runner.run(List.of("git", "-C", seed.toString(), "config", "user.email", "gate@example.com"), null, Map.of(), java.time.Duration.ofSeconds(5));
        runner.run(List.of("git", "-C", seed.toString(), "add", "."), null, Map.of(), java.time.Duration.ofSeconds(5));
        runner.run(List.of("git", "-C", seed.toString(), "commit", "-m", "bk"), null, Map.of(), java.time.Duration.ofSeconds(5));
        runner.run(List.of("git", "-C", seed.toString(), "push", "origin", "HEAD:refs/heads/main"), null, Map.of(), java.time.Duration.ofSeconds(5));

        FsS3Store s3 = new FsS3Store(gateHome.resolve("s3"));
        BackupService backup = new BackupService(ds, authRepo, gateHome, s3);
        var result = backup.backup();
        assertNotNull(result.backupId());
        assertTrue(Files.exists(result.dbBackup()), "db backup file must exist, not ticket_count text");
        assertTrue(Files.exists(result.gitBundle()), "git bundle must exist");
        assertTrue(Files.size(result.dbBackup()) > 1024, "db file must be real SQLite, not tiny text");
        assertTrue(backup.verify(result.backupId()));
        // Verify S3 stored artifacts
        assertTrue(s3.head("backup/" + result.backupId() + "/gate.db").isPresent());
        assertTrue(s3.head("backup/" + result.backupId() + "/auth.bundle").isPresent());

        // Simulate restore pre-check passes when no RUNNING tasks (must be 0)
        // First ensure no RUNNING tasks: failOrphaned already cleared, but we have no tasks yet so ok
        // Clean any previous RUNNING from earlier? Our DB has no gate_task RUNNING yet
        assertDoesNotThrow(() -> backup.restoreDbPreCheck());

        // Real restore to isolated locations and verify restored DB, not original
        Path restoreHome = tempDir.resolve("restoreHome");
        Path restoreAuth = tempDir.resolve("restoreAuth.git");
        Files.createDirectories(restoreHome);
        // Need to ensure S3 is shared: backup's s3 is at gateHome/s3, restore s3 should be same instance
        // Use same s3 instance for restore
        assertDoesNotThrow(() -> backup.restore(result.backupId(), restoreHome, restoreAuth));
        // Verify restored DB file exists and contains ticket
        Path restoredDb = restoreHome.resolve("gate.db");
        assertTrue(Files.exists(restoredDb), "restored gate.db must exist");
        // Open restored DB and query ticket
        var restoredDs = gate.adapters.store.SqliteDataSourceFactory.create(restoredDb);
        gate.adapters.store.SqliteDataSourceFactory.migrate(restoredDs);
        // Note: migrate will not overwrite restored data; we need to query directly via JdbcTemplate on restored DS
        var restoredJdbc = new org.springframework.jdbc.core.JdbcTemplate(restoredDs);
        Integer cntRestored = restoredJdbc.queryForObject("SELECT count(*) FROM ticket WHERE ticket_no='T-BK-1'", Integer.class);
        assertEquals(1, cntRestored, "restored DB must contain T-BK-1");
        // Verify restored Git has the commit
        var restoredRunner = new gate.adapters.process.ProcessRunnerImpl(tempDir);
        var tipRes = restoredRunner.run(List.of("git", "--git-dir", restoreAuth.toString(), "rev-parse", "refs/heads/main"), null, Map.of(), java.time.Duration.ofSeconds(5));
        assertTrue(tipRes.ok(), "restored auth repo must have refs/heads/main");
        // Also verify original DB still has ticket
        Integer cntOrig = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE ticket_no='T-BK-1'", Integer.class);
        assertEquals(1, cntOrig);

        // Enqueue a RUNNING task then pre-check must fail (isolated from restore)
        var taskRepo = new JdbcGateTaskRepository(jdbc, () -> Instant.parse("2026-08-20T10:00:00Z"));
        taskRepo.registerWithKey("publish", "T-BK-1", null, "idem-bk", "digest");
        assertThrows(Exception.class, () -> backup.restoreDbPreCheck());
        // Clean up RUNNING for other tests: mark failed
        jdbc.update("UPDATE gate_task SET status='FAILED', finished_at=datetime('now') WHERE ticket_no='T-BK-1'");
    }

    @Test
    void testHealthAndMetricsAndSlo() throws Exception {
        Path authRepo = tempDir.resolve("auth2.git");
        Files.createDirectories(authRepo);
        var runner = new gate.adapters.process.ProcessRunnerImpl(tempDir);
        runner.run(List.of("git", "init", "--bare", authRepo.toString()), null, Map.of(), java.time.Duration.ofSeconds(5));
        FsS3Store s3 = new FsS3Store(tempDir.resolve("s3b"));
        LocalKmsService kms = new LocalKmsService("k1", "s");
        gate.adapters.git.GitCli git = new gate.adapters.git.GitCli(runner);
        var refObs = new gate.adapters.git.GitCliRefObserver(git);
        HealthService health = new HealthService(ds, authRepo, s3, kms, refObs);
        assertEquals("ok", health.livez().get("status"));
        Map<String,Object> readyz = health.readyz();
        assertEquals("ok", readyz.get("status"));
        assertEquals(true, readyz.get("db_writable"));
        Map<String,Object> deps = health.dependencies();
        assertNotNull(deps.get("auth_repo_exists"));

        InMemoryMetrics metrics = new InMemoryMetrics();
        metrics.counter("gate_http_requests_total", 5, Map.of("route","/api/tickets"));
        metrics.histogram("gate_http_request_duration_ms", 80, Map.of("route","/api/tickets"));
        assertTrue(metrics.getCounter("gate_http_requests_total") >= 5);
        assertTrue(metrics.prometheusText().contains("gate_http_requests_total"));

        SloService slo = new SloService();
        var results = slo.evaluate(Map.of("short_read_p95", 150L, "short_write_p95", 250L, "sse_visible_p95", 1200L), Map.of("control_plane", 0.9995), Map.of());
        assertEquals(4, results.size());
        assertTrue(slo.allGreen(results));

        // Breach case
        var breached = slo.evaluate(Map.of("short_read_p95", 500L), Map.of("control_plane", 0.998), Map.of());
        assertFalse(slo.allGreen(breached));
    }
}
