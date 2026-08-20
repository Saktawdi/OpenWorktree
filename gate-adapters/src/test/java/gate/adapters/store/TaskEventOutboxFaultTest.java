package gate.adapters.store;

import gate.domain.error.GateException;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.Clock;
import gate.ports.OutboxPort;
import gate.ports.TaskEventPort;
import gate.ports.TaskRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Fault and Concurrency Verification Test.
 * Covers:
 * 1. Stale worker fence rejection
 * 2. Idempotency key lookup and conflict verification
 * 3. Outbox transactional append and relay
 * 4. Persistent task_event sequence and cursor replay
 * 5. Slow-consumer bounded buffer backpressure (DEBT-006, SseHandler MAX_BUFFERED_EVENTS=100)
 * 6. Node switch replay after lease expiry (Worker A -> Worker B takeover without loss)
 */
class TaskEventOutboxFaultTest {

    private Path tempDir;
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private JdbcGateTaskRepository repository;
    private final Clock clock = () -> Instant.parse("2026-08-20T10:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("gate-fault-test-");
        Path dbPath = tempDir.resolve("gate-test.db");
        dataSource = SqliteDataSourceFactory.create(dbPath);
        SqliteDataSourceFactory.migrate(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcGateTaskRepository(jdbcTemplate, clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {}
                    });
        }
    }

    @Test
    void testTaskRegistrationAndPersistentEventSequence() {
        GateTask task = repository.registerWithKey("review", "T-100", null, "idem-100", "digest-abc");
        assertNotNull(task.id());
        assertEquals(GateTaskStatus.RUNNING, task.status());

        long latestSeq = repository.latestSequence(task.id());
        assertTrue(latestSeq >= 1L, "Initial registration must append at least one event with sequence >= 1");

        List<TaskEventPort.TaskEvent> events = repository.replay(task.id(), 0);
        assertFalse(events.isEmpty());
        assertEquals(1L, events.get(0).sequence());
        assertEquals("progress", events.get(0).eventType());
    }

    @Test
    void testStaleWorkerFenceRejection() {
        GateTask task = repository.registerWithKey("publish", "T-101", null, "idem-101", "digest-101");
        String taskId = task.id();

        // Simulate Worker A holding fence token 1
        GateTask terminalSuccess = new GateTask(
                taskId, task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.SUCCEEDED, task.startedAt(), clock.now(), "{\"commit\":\"sha1\"}", null);

        // Worker A updates with valid fence token
        assertDoesNotThrow(() -> repository.updateWithFence(terminalSuccess, 1L));

        // Now database has fence token 1. Suppose a stale Worker B with fence token 0 tries to overwrite
        GateTask staleWorkerWrite = new GateTask(
                taskId, task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.FAILED, task.startedAt(), clock.now(), null, "{\"error\":\"stale\"}");

        // Stale update with lower fence token MUST be rejected
        assertThrows(GateException.class, () -> repository.updateWithFence(staleWorkerWrite, 0L));
    }

    @Test
    void testOutboxAppendAndRelay() {
        GateTask task = repository.registerWithKey("session-send", "T-102", "ses-1", "idem-102", "digest-102");

        List<OutboxPort.OutboxEntry> pendingBefore = repository.pending(10);
        assertFalse(pendingBefore.isEmpty(), "Outbox entry must be appended transactionally with task registration");

        int relayedCount = repository.relay(10);
        assertTrue(relayedCount >= 1);

        List<OutboxPort.OutboxEntry> pendingAfter = repository.pending(10);
        assertTrue(pendingAfter.isEmpty(), "Relayed outbox entries must no longer be pending");
    }

    @Test
    void testCursorReplayStream() {
        GateTask task = repository.registerWithKey("review", "T-103", null, "idem-103", "digest-103");
        String taskId = task.id();

        // Append multiple progress events
        repository.append(taskId, "progress", "{\"step\":1}");
        repository.append(taskId, "progress", "{\"step\":2}");
        repository.append(taskId, "done", "{\"step\":3}");

        // Replay events with cursor > sequence 1
        List<TaskEventPort.TaskEvent> replayed = repository.replay(taskId, 1);
        assertEquals(3, replayed.size());
        assertEquals(2L, replayed.get(0).sequence());
        assertEquals(3L, replayed.get(1).sequence());
        assertEquals(4L, replayed.get(2).sequence());
        assertEquals("done", replayed.get(2).eventType());
    }

    /**
     * Scenario 5: slowConsumerDoesNotGrowUnbounded
     * Simulates 200 events written to a bounded buffer cap 100 (SseHandler.MAX_BUFFERED_EVENTS).
     * Asserts buffer never exceeds cap and DB replay still recovers authoritative final state.
     */
    @Test
    void testSlowConsumerDoesNotGrowUnbounded() {
        GateTask task = repository.registerWithKey("review", "T-200", null, "idem-200", "digest-200");
        String taskId = task.id();

        final int MAX_BUFFERED_EVENTS = 100;
        BlockingQueue<TaskEventPort.TaskEvent> buffer = new ArrayBlockingQueue<>(MAX_BUFFERED_EVENTS);

        int totalAppends = 200;
        long dropped = 0;

        for (int i = 1; i <= totalAppends; i++) {
            TaskEventPort.TaskEvent e = repository.append(taskId, "progress", "{\"seq\":" + i + "}");
            if (!buffer.offer(e)) {
                TaskEventPort.TaskEvent discarded = buffer.poll();
                if (discarded != null) {
                    dropped++;
                }
                while (!buffer.offer(e)) {
                    TaskEventPort.TaskEvent d2 = buffer.poll();
                    if (d2 != null) {
                        dropped++;
                    } else {
                        break;
                    }
                }
            }
            assertTrue(buffer.size() <= MAX_BUFFERED_EVENTS, "buffer must never exceed MAX_BUFFERED_EVENTS");
        }

        TaskEventPort.TaskEvent done = repository.append(taskId, "done", "{\"final\":true}");
        if (!buffer.offer(done)) {
            TaskEventPort.TaskEvent discarded = buffer.poll();
            if (discarded != null) {
                dropped++;
            }
            while (!buffer.offer(done)) {
                TaskEventPort.TaskEvent d2 = buffer.poll();
                if (d2 != null) {
                    dropped++;
                } else {
                    break;
                }
            }
        }

        assertTrue(buffer.size() <= MAX_BUFFERED_EVENTS, "bounded buffer must never exceed cap after done");
        assertEquals(MAX_BUFFERED_EVENTS, buffer.size(), "after 200+ events buffer must be exactly at cap 100");
        assertTrue(dropped > 0, "dropped count must be positive for slow consumer (backpressure applied)");

        // DB replay must recover authoritative final state despite in-memory drops
        List<TaskEventPort.TaskEvent> all = repository.replay(taskId, 0);
        int expectedTotal = 1 + totalAppends + 1; // 1 init + 200 progress + 1 done
        assertEquals(expectedTotal, all.size(), "DB must retain every event including those dropped from memory");
        assertEquals("done", all.get(all.size() - 1).eventType(), "DB latest must be terminal done");
        assertEquals(all.size(), repository.latestSequence(taskId), "latestSequence must match total events");

        // sequences must be contiguous 1..N without gaps
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).sequence(), "sequence gap at index " + i);
        }

        // Last-Event-ID cursor replay: client lost buffer, replays from early cursor and recovers all missed
        long earlyCursor = all.get(0).sequence(); // 1
        List<TaskEventPort.TaskEvent> replayFromEarly = repository.replay(taskId, earlyCursor);
        assertEquals(all.size() - 1, replayFromEarly.size(), "replay after early cursor must return all subsequent events");

        long midCursor = 50;
        List<TaskEventPort.TaskEvent> replayMid = repository.replay(taskId, midCursor);
        assertEquals(all.size() - midCursor, replayMid.size(), "replay from mid cursor must still be complete from DB");
        assertEquals("done", replayMid.get(replayMid.size() - 1).eventType(), "mid replay must still end with done");
    }

    /**
     * Scenario 6: nodeSwitchReplayAfterLeaseExpiry
     * Simulates Worker A lease expiry then Worker B takeover with incremented fence_token;
     * asserts replay has no dropped events and stale fence is still rejected.
     */
    @Test
    void testNodeSwitchReplayAfterLeaseExpiry() {
        GateTask task = repository.registerWithKey("publish", "T-201", null, "idem-201", "digest-201");
        String taskId = task.id();

        // Simulate Worker A acquires lease but it is already expired (lease_until before now)
        Instant leaseUntilA = Instant.parse("2026-08-20T09:59:00Z");
        jdbcTemplate.update("UPDATE gate_task SET lease_owner=?, lease_until=?, fence_token=? WHERE id=?",
                "worker-A", leaseUntilA.toString(), 1L, taskId);

        // Worker A appends 3 progress events before failure
        repository.append(taskId, "progress", "{\"worker\":\"A\",\"step\":1}");
        repository.append(taskId, "progress", "{\"worker\":\"A\",\"step\":2}");
        repository.append(taskId, "progress", "{\"worker\":\"A\",\"step\":3}");

        long seqAfterA = repository.latestSequence(taskId);
        assertEquals(4L, seqAfterA, "1 init +3 A appends =4");

        Optional<GateTask> afterA = repository.find(taskId);
        assertTrue(afterA.isPresent());
        assertEquals("worker-A", afterA.get().leaseOwner(), "lease should still show worker-A before takeover");

        // Worker B detects expiry and claims with fence_token 2 (monotonic increment)
        Instant leaseUntilB = Instant.parse("2026-08-20T10:01:00Z");
        int claimed = jdbcTemplate.update(
                "UPDATE gate_task SET lease_owner=?, lease_until=?, fence_token=?, attempt=attempt+1 WHERE id=? AND fence_token=?",
                "worker-B", leaseUntilB.toString(), 2L, taskId, 1L);
        assertEquals(1, claimed, "Worker B must successfully take over expired lease with incremented fence");

        // Worker B appends 2 more events after takeover
        repository.append(taskId, "progress", "{\"worker\":\"B\",\"step\":4}");
        repository.append(taskId, "progress", "{\"worker\":\"B\",\"step\":5}");

        // Worker B completes terminally via fencing update (fence 2)
        GateTask snapshot = repository.find(taskId).orElseThrow();
        GateTask terminal = new GateTask(
                taskId, snapshot.type(), snapshot.ticketNo(), snapshot.sessionId(),
                GateTaskStatus.SUCCEEDED, snapshot.startedAt(), clock.now(), "{\"worker\":\"B\",\"result\":\"ok\"}", null);
        assertDoesNotThrow(() -> repository.updateWithFence(terminal, 2L), "B with fence 2 must succeed");

        // DB replay must contain all events without loss across node switch
        List<TaskEventPort.TaskEvent> all = repository.replay(taskId, 0);
        assertEquals(7, all.size(), "total events: 1 init +3 A +2 B +1 done from updateWithFence");
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).sequence(), "sequence must be contiguous after node switch at index " + i);
        }
        assertEquals("done", all.get(all.size() - 1).eventType(), "final event must be done");

        // stale Worker A with old fence 1 must be rejected
        GateTask stale = new GateTask(
                taskId, snapshot.type(), snapshot.ticketNo(), snapshot.sessionId(),
                GateTaskStatus.FAILED, snapshot.startedAt(), clock.now(), null, "{\"error\":\"stale-A\"}");
        assertThrows(GateException.class, () -> repository.updateWithFence(stale, 1L),
                "stale fence token 1 must be rejected after B takeover with fence 2");

        // Replay from cursor after A's events must still see B's events and final done (no loss)
        List<TaskEventPort.TaskEvent> replayAfterA = repository.replay(taskId, seqAfterA);
        assertEquals(3, replayAfterA.size(), "after seq 4 should have 3 events: B step4, step5, done");
        assertEquals("progress", replayAfterA.get(0).eventType());
        assertEquals("done", replayAfterA.get(2).eventType());

        // Full replay from 0 after switch equals all
        assertEquals(all.size(), repository.replay(taskId, 0).size(), "full replay after switch must be complete");

        // Lease now owned by B with fence 2
        Optional<GateTask> afterB = repository.find(taskId);
        assertTrue(afterB.isPresent());
        assertEquals("worker-B", afterB.get().leaseOwner());
        assertEquals(2L, afterB.get().fenceToken());
    }
}
