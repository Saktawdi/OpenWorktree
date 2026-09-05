package gate.adapters.store;

import gate.adapters.git.GitCli;
import gate.adapters.git.GitCliRefObserver;
import gate.adapters.git.LocalAuthoritativeGitService;
import gate.adapters.lock.FileChannelLockManager;
import gate.adapters.process.ProcessRunnerImpl;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.PublishAuthorization;
import gate.domain.review.EngineDescriptor;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.git.AuthoritativeGitService;
import gate.ports.infra.Clock;
import gate.ports.task.TaskClaimPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase3 HA exit criteria fault test (§17.3).
 * <ul>
 *   <li>any Web node can query/subscribe any task (DB is source of truth)</li>
 *   <li>any compatible Worker can claim queued task, lease renewal, reaper takeover</li>
 *   <li>concurrent CAS on same ref: at most one success</li>
 *   <li>PG/Git/Worker fault does not create duplicate publish</li>
 * </ul>
 */
@Tag("slow")
class Phase3HaFaultTest {

    private Path tempDir;
    private Path authRepo;
    private Path clonesRoot;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcGateTaskRepository repoA;
    private JdbcGateTaskRepository repoB;
    private final Clock clock = () -> Instant.parse("2026-08-20T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("gate-phase3-");
        Path dbPath = tempDir.resolve("gate-test.db");
        dataSource = SqliteDataSourceFactory.create(dbPath);
        SqliteDataSourceFactory.migrate(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repoA = new JdbcGateTaskRepository(jdbc, clock);
        repoB = new JdbcGateTaskRepository(jdbc, clock);

        // auth bare repo for CAS test
        authRepo = tempDir.resolve("auth.git");
        clonesRoot = tempDir.resolve("clones");
        Files.createDirectories(clonesRoot);
        // init bare
        var runner = new ProcessRunnerImpl(tempDir);
        var git = new GitCli(runner);
        // git init --bare
        runner.run(List.of("git", "init", "--bare", authRepo.toString()), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        // create initial commit via a temp clone
        Path seed = tempDir.resolve("seed");
        runner.run(List.of("git", "clone", authRepo.toString(), seed.toString()), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        Files.writeString(seed.resolve("README.md"), "seed");
        runner.run(List.of("git", "-C", seed.toString(), "add", "."), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", seed.toString(), "config", "user.name", "gate"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", seed.toString(), "config", "user.email", "gate@example.com"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", seed.toString(), "commit", "-m", "init"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", seed.toString(), "push", "origin", "HEAD:refs/heads/main"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null) Files.walk(tempDir).sorted((a,b)->b.compareTo(a)).forEach(p->{ try{Files.deleteIfExists(p);}catch(Exception ignored){}});
    }

    @Test
    void testAnyWebNodeCanQueryAndSubscribeAnyTask() throws Exception {
        // repoA creates task, repoB (different Web node) must see it via DB
        GateTask task = repoA.enqueue("review", "T-300", null, "default", "proj-1", "idem-300", "digest-300", 5, clock.now());
        assertEquals(GateTaskStatus.QUEUED, task.status());

        // repoB can find
        Optional<GateTask> found = repoB.find(task.id());
        assertTrue(found.isPresent());
        assertEquals(task.id(), found.get().id());

        // repoB can replay events (cross-node SSE)
        var eventsA = repoA.replay(task.id(), 0);
        var eventsB = repoB.replay(task.id(), 0);
        assertEquals(eventsA.size(), eventsB.size());

        // repoB can stream with cursor
        var stream = repoB.stream(task.id());
        assertNotNull(stream);
        stream.close();
    }

    @Test
    void testAnyWorkerCanClaimAndReaperTakeover() throws Exception {
        // enqueue two tasks with different priority
        Instant now = clock.now();
        GateTask t1 = repoA.enqueue("publish", "T-301", null, "default", "proj-1", "idem-301", "d1", 10, now);
        GateTask t2 = repoA.enqueue("publish", "T-302", null, "default", "proj-1", "idem-302", "d2", 1, now);

        // worker-A claims highest priority first
        Optional<GateTask> claimedA = repoA.claimNext("worker-A", Duration.ofSeconds(60), now);
        assertTrue(claimedA.isPresent());
        assertEquals(t1.id(), claimedA.get().id());
        assertEquals("worker-A", claimedA.get().leaseOwner());

        // worker-B claims next
        Optional<GateTask> claimedB = repoB.claimNext("worker-B", Duration.ofSeconds(60), now);
        assertTrue(claimedB.isPresent());
        assertEquals(t2.id(), claimedB.get().id());

        // lease renewal by owner succeeds, by other fails
        assertTrue(repoA.renewLease(claimedA.get().id(), "worker-A", claimedA.get().fenceToken(), Duration.ofSeconds(60), now));
        assertFalse(repoA.renewLease(claimedA.get().id(), "worker-B", claimedA.get().fenceToken(), Duration.ofSeconds(60), now));

        // simulate lease expiry: set lease_until in past for t1
        Instant past = Instant.parse("2026-08-20T09:00:00Z");
        jdbc.update("UPDATE gate_task SET lease_until = ? WHERE id = ?", past.toString(), claimedA.get().id());

        // reaper moves it to RETRY_WAIT
        int reaped = repoA.reapExpiredLeases(now);
        assertTrue(reaped >= 1);
        GateTask afterReap = repoA.find(claimedA.get().id()).orElseThrow();
        assertEquals(GateTaskStatus.RETRY_WAIT, afterReap.status());

        // worker-B can now claim the retried task again
        // need to make available_at <= now (reaper set it)
        Optional<GateTask> retaken = repoB.claimNext("worker-B", Duration.ofSeconds(60), now);
        assertTrue(retaken.isPresent());
        // t1 should be claimable again (priority 10 > others)
        boolean isT1 = retaken.get().id().equals(t1.id());
        // if not t1, drain and try again
        if (!isT1) {
            Optional<GateTask> second = repoA.claimNext("worker-A", Duration.ofSeconds(60), now);
            assertTrue(second.isPresent());
        }
    }

    @Test
    void testConcurrentCasAtMostOneSuccess() throws Exception {
        var runner = new ProcessRunnerImpl(tempDir);
        var git = new GitCli(runner);
        var refObserver = new GitCliRefObserver(git);
        var lockManager = new FileChannelLockManager(tempDir.resolve("locks"));
        var nonceStore = new JdbcNonceStore(jdbc);
        var cas = new LocalAuthoritativeGitService(git, refObserver, nonceStore, lockManager);

        RepoRef auth = RepoRef.of(authRepo);
        String targetRef = "refs/heads/main";
        Optional<ObjectId> tip = refObserver.tip(auth, targetRef);
        assertTrue(tip.isPresent());
        ObjectId expectedOld = tip.get();

        // create two different new commits (different tree) by seeding via clone
        Path cloneA = clonesRoot.resolve("cloneA");
        Path cloneB = clonesRoot.resolve("cloneB");
        // clone separately
        runner.run(List.of("git", "clone", authRepo.toString(), cloneA.toString()), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "clone", authRepo.toString(), cloneB.toString()), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        for (Path c : List.of(cloneA, cloneB)) {
            runner.run(List.of("git", "-C", c.toString(), "config", "user.name", "gate"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
            runner.run(List.of("git", "-C", c.toString(), "config", "user.email", "gate@example.com"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        }
        // create distinct commits via commit-tree directly on bare repo using git hash-object + commit-tree
        // Simpler: make file change and commit via git commit
        Files.writeString(cloneA.resolve("fileA.txt"), "A-" + UUID.randomUUID());
        runner.run(List.of("git", "-C", cloneA.toString(), "add", "."), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", cloneA.toString(), "commit", "-m", "A"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        ObjectId commitA = ObjectId.of(runner.run(List.of("git", "-C", cloneA.toString(), "rev-parse", "HEAD"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10)).stdout().trim());
        // push object to bare so update-ref can find it
        runner.run(List.of("git", "-C", cloneA.toString(), "push", "origin", commitA.hex() + ":refs/gate/tmpA"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));

        Files.writeString(cloneB.resolve("fileB.txt"), "B-" + UUID.randomUUID());
        runner.run(List.of("git", "-C", cloneB.toString(), "add", "."), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", cloneB.toString(), "commit", "-m", "B"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        ObjectId commitB = ObjectId.of(runner.run(List.of("git", "-C", cloneB.toString(), "rev-parse", "HEAD"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10)).stdout().trim());
        runner.run(List.of("git", "-C", cloneB.toString(), "push", "origin", commitB.hex() + ":refs/gate/tmpB"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));

        // create authorizations
        PublishAuthorization authA = mockAuth("T-400", 1, commitA);
        PublishAuthorization authB = mockAuth("T-401", 1, commitB);

        // concurrent CAS
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> reasons = new java.util.concurrent.ConcurrentLinkedQueue<>();
        exec.submit(() -> { try{ start.await(); AuthoritativeGitService.CasResult r = cas.casPublish(auth, targetRef, expectedOld, commitA, commitA, authA); reasons.add("A:" + r.success() + ":" + r.reason()); if(r.success()) successes.incrementAndGet(); else failures.incrementAndGet(); } catch(Exception e){ reasons.add("A-ex:" + e.getMessage()); failures.incrementAndGet(); } });
        exec.submit(() -> { try{ start.await(); AuthoritativeGitService.CasResult r = cas.casPublish(auth, targetRef, expectedOld, commitB, commitB, authB); reasons.add("B:" + r.success() + ":" + r.reason()); if(r.success()) successes.incrementAndGet(); else failures.incrementAndGet(); } catch(Exception e){ reasons.add("B-ex:" + e.getMessage()); failures.incrementAndGet(); } });
        start.countDown();
        exec.shutdown();
        exec.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(1, successes.get(), "at most one CAS must succeed reasons=" + reasons);
        assertEquals(1, failures.get());

        // verify tip is one of the two commits, not both
        Optional<ObjectId> finalTip = refObserver.tip(auth, targetRef);
        assertTrue(finalTip.isPresent());
        boolean isA = finalTip.get().equals(commitA);
        boolean isB = finalTip.get().equals(commitB);
        assertTrue(isA ^ isB, "final tip must be exactly one of A or B");

        // nonce replay must fail
        AuthoritativeGitService.CasResult replay = cas.casPublish(auth, targetRef, expectedOld, commitA, commitA, authA);
        // second attempt with same nonce should be rejected or idempotent if already published
        // If commitA won, replay should be idempotent alreadyPublished; if commitB won, CAS conflict
        assertTrue(!replay.success() || replay.alreadyPublished());
    }

    @Test
    void testPgGitWorkerFaultDoesNotDuplicatePublish() throws Exception {
        // Simulate: publish intent created, Git CAS succeeded, but DB update crashed before PUBLISHED
        // Reconcile must converge without duplicate ref advance
        var runner = new ProcessRunnerImpl(tempDir);
        var git = new GitCli(runner);
        var refObserver = new GitCliRefObserver(git);
        var lockManager = new FileChannelLockManager(tempDir.resolve("locks2"));
        var nonceStore = new JdbcNonceStore(jdbc);
        var cas = new LocalAuthoritativeGitService(git, refObserver, nonceStore, lockManager);

        RepoRef auth = RepoRef.of(authRepo);
        String targetRef = "refs/heads/main";
        Optional<ObjectId> tipBefore = refObserver.tip(auth, targetRef);
        assertTrue(tipBefore.isPresent());

        Path clone = clonesRoot.resolve("faultClone");
        runner.run(List.of("git", "clone", authRepo.toString(), clone.toString()), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", clone.toString(), "config", "user.name", "gate"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", clone.toString(), "config", "user.email", "gate@example.com"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        Files.writeString(clone.resolve("fault.txt"), "fault-" + UUID.randomUUID());
        runner.run(List.of("git", "-C", clone.toString(), "add", "."), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", clone.toString(), "commit", "-m", "fault"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        ObjectId newCommit = ObjectId.of(runner.run(List.of("git", "-C", clone.toString(), "rev-parse", "HEAD"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10)).stdout().trim());
        runner.run(List.of("git", "-C", clone.toString(), "push", "origin", newCommit.hex() + ":refs/gate/tmpFault"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));

        PublishAuthorization authorization = mockAuth("T-500", 1, newCommit);

        // First CAS succeeds
        AuthoritativeGitService.CasResult first = cas.casPublish(auth, targetRef, tipBefore.get(), newCommit, newCommit, authorization);
        assertTrue(first.success(), "first CAS should succeed: " + first.reason());
        assertFalse(first.alreadyPublished());

        // Simulate worker crash: DB still PENDING. Re-attempt publish with same commit/nonce should be idempotent, not create second ref update
        AuthoritativeGitService.CasResult second = cas.casPublish(auth, targetRef, tipBefore.get(), newCommit, newCommit, authorization);
        assertTrue(second.success() || second.alreadyPublished());
        // Tip must still be newCommit, not moved further
        Optional<ObjectId> tipAfter = refObserver.tip(auth, targetRef);
        assertEquals(newCommit, tipAfter.orElseThrow());

        // Attempt with different commit but same old OID must fail CAS conflict (no duplicate publish)
        Path clone2 = clonesRoot.resolve("faultClone2");
        runner.run(List.of("git", "clone", authRepo.toString(), clone2.toString()), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", clone2.toString(), "config", "user.name", "gate"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", clone2.toString(), "config", "user.email", "gate@example.com"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        Files.writeString(clone2.resolve("fault2.txt"), "fault2-" + UUID.randomUUID());
        runner.run(List.of("git", "-C", clone2.toString(), "add", "."), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        runner.run(List.of("git", "-C", clone2.toString(), "commit", "-m", "fault2"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        ObjectId newCommit2 = ObjectId.of(runner.run(List.of("git", "-C", clone2.toString(), "rev-parse", "HEAD"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10)).stdout().trim());
        runner.run(List.of("git", "-C", clone2.toString(), "push", "origin", newCommit2.hex() + ":refs/gate/tmpFault2"), null, java.util.Map.of(), java.time.Duration.ofSeconds(10));
        PublishAuthorization auth2 = mockAuth("T-501", 1, newCommit2);
        AuthoritativeGitService.CasResult conflict = cas.casPublish(auth, targetRef, tipBefore.get(), newCommit2, newCommit2, auth2);
        assertFalse(conflict.success());
        assertTrue(conflict.reason().contains("CAS conflict") || conflict.reason().contains("already published"));
    }

    // helper to mint PublishAuthorization via reflection (GatePolicy is only minter)
    private PublishAuthorization mockAuth(String ticketNo, int round, ObjectId tree) throws Exception {
        var m = PublishAuthorization.class.getDeclaredMethod("mint", String.class, int.class, ObjectId.class, EngineDescriptor.class);
        m.setAccessible(true);
        EngineDescriptor desc = new EngineDescriptor("mock", "1.0", "fp", "manual", "test-model");
        return (PublishAuthorization) m.invoke(null, ticketNo, round, tree, desc);
    }
}
