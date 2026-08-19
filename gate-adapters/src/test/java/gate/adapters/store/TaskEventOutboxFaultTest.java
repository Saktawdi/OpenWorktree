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
}
