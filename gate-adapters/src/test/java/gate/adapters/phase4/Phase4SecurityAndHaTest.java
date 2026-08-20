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
    }

    @Test
    void testWormAuditArchive() throws Exception {
        Path auditPath = tempDir.resolve("audit.log");
        var chain = new HashChainAuditLog(auditPath);
        chain.append(AuditEvent.of(Instant.parse("2026-08-20T10:00:00Z"), "publish.done", "T-1", 1, Map.of("commit","abc")));
        chain.append(AuditEvent.of(Instant.parse("2026-08-20T10:01:00Z"), "publish.done", "T-2", 1, Map.of("commit","def")));
        assertTrue(chain.verifyChain());

        LocalKmsService kms = new LocalKmsService("k1", "secret1");
        WormAuditArchive archive = new WormAuditArchive(auditPath, jdbc, kms);
        assertNull(archive.detectTampering());
        var cp = archive.createCheckpoint("k1");
        assertNotNull(cp);
        assertEquals(2, cp.eventCount());
        assertTrue(archive.verifyChain());
        assertEquals(1, archive.listCheckpoints().size());

        // Tamper detection: corrupt file
        Files.writeString(auditPath, "corrupted\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertNotNull(archive.detectTampering());

        // Recovery: new archive should detect
        assertFalse(archive.verifyChain());
    }

    @Test
    void testBackupAndRestore() throws Exception {
        Path authRepo = tempDir.resolve("auth.git");
        Path gateHome = tempDir.resolve("gateHome");
        Files.createDirectories(gateHome);
        // init bare auth repo
        var runner = new gate.adapters.process.ProcessRunnerImpl(tempDir);
        runner.run(List.of("git", "init", "--bare", authRepo.toString()), null, Map.of(), java.time.Duration.ofSeconds(5));
        // seed DB with ticket
        jdbc.update("INSERT INTO ticket(ticket_no,title,target_ref,clone_path,stage,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                "T-BK-1","bk","refs/heads/main",tempDir.resolve("clone").toString(),"DONE", Instant.now().toString(), Instant.now().toString());
        FsS3Store s3 = new FsS3Store(gateHome.resolve("s3"));
        BackupService backup = new BackupService(ds, authRepo, gateHome, s3);
        var result = backup.backup();
        assertNotNull(result.backupId());
        assertTrue(Files.exists(result.dbBackup()) || result.manifestKey() != null);
        assertTrue(backup.verify(result.backupId()));

        // Verify publish reconcile after restore simulation: ensure ticket still readable
        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM ticket WHERE ticket_no='T-BK-1'", Integer.class);
        assertEquals(1, cnt);
        // Simulate restore pre-check passes when no RUNNING tasks
        assertDoesNotThrow(() -> backup.restoreDbPreCheck());

        // Enqueue a RUNNING task then pre-check should fail
        var taskRepo = new JdbcGateTaskRepository(jdbc, () -> Instant.parse("2026-08-20T10:00:00Z"));
        taskRepo.registerWithKey("publish", "T-BK-1", null, "idem-bk", "digest");
        // register leaves RUNNING
        assertThrows(Exception.class, () -> backup.restoreDbPreCheck());
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
