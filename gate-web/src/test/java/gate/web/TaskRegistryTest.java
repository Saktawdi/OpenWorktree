package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.clock.SystemClock;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.TaskRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S2 TaskRegistry tests (执行文档-后端-web §9.3): replay + live events, terminal stream closure,
 * persistence and startup orphan reconciliation.
 */
class TaskRegistryTest {

    private Path root;
    private JdbcGateTaskRepository registry;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-task-registry-");
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        registry = new JdbcGateTaskRepository(new JdbcTemplate(ds), new SystemClock());
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            // best effort; the test thread may still be blocked by a stream bug
        }
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    @Test
    void register_find_update_persists() {
        GateTask t = registry.register("review", "T-1", null);
        assertNotNull(t.id());
        assertEquals(GateTaskStatus.RUNNING, t.status());

        GateTask found = registry.find(t.id()).orElseThrow();
        assertEquals(t.id(), found.id());
        assertEquals("T-1", found.ticketNo());
        assertEquals(GateTaskStatus.RUNNING, found.status());

        Instant doneAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        GateTask terminal = new GateTask(t.id(), t.type(), t.ticketNo(), t.sessionId(),
                GateTaskStatus.SUCCEEDED, t.startedAt(), doneAt, "{\"ok\":true}", null);
        registry.update(terminal);

        GateTask after = registry.find(t.id()).orElseThrow();
        assertEquals(GateTaskStatus.SUCCEEDED, after.status());
        assertEquals("{\"ok\":true}", after.resultJson());
        assertEquals(doneAt, after.finishedAt());
    }

    @Test
    void stream_replays_then_live_and_ends_after_done() throws Exception {
        GateTask t = registry.register("publish", "T-2", null);

        // A subscriber must see the initial progress event that was published before it subscribed.
        Future<List<TaskRegistry.GateTaskEvent>> future = executor.submit(() -> {
            List<TaskRegistry.GateTaskEvent> events = new ArrayList<>();
            registry.stream(t.id()).forEach(events::add);
            return events;
        });

        // Give the stream time to open and replay the initial event.
        Thread.sleep(200);

        GateTask progress = new GateTask(t.id(), t.type(), t.ticketNo(), t.sessionId(),
                GateTaskStatus.RUNNING, t.startedAt(), null, "{\"percent\":50}", null);
        registry.update(progress);

        Instant doneAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        GateTask terminal = new GateTask(t.id(), t.type(), t.ticketNo(), t.sessionId(),
                GateTaskStatus.SUCCEEDED, t.startedAt(), doneAt, "{\"ok\":true}", null);
        registry.update(terminal);

        List<TaskRegistry.GateTaskEvent> events = future.get(5, TimeUnit.SECONDS);
        assertFalse(events.isEmpty());
        assertEquals(t.id(), events.get(0).taskId());
        assertEquals("progress", events.get(0).kind());
        assertTrue(events.stream().anyMatch(e -> "progress".equals(e.kind())), "should see live progress");
        assertEquals("done", events.get(events.size() - 1).kind());
        assertTrue(events.get(events.size() - 1).payloadJson().contains("\"status\":\"SUCCEEDED\""));
    }

    @Test
    void stream_for_unknown_id_is_empty() {
        assertFalse(registry.stream("no-such-id").findAny().isPresent());
    }

    @Test
    void stream_for_terminal_task_without_memory_channel_synthesizes_done() {
        GateTask t = registry.register("review", "T-3", null);
        Instant doneAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        registry.update(new GateTask(t.id(), t.type(), t.ticketNo(), t.sessionId(),
                GateTaskStatus.FAILED, t.startedAt(), doneAt, null, "{\"error_code\":12}"));

        // Simulate a fresh repository (restart) so the in-memory channel is gone.
        DataSource ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcGateTaskRepository restarted = new JdbcGateTaskRepository(new JdbcTemplate(ds), new SystemClock());

        List<TaskRegistry.GateTaskEvent> events = restarted.stream(t.id()).toList();
        assertEquals(1, events.size());
        assertEquals("done", events.get(0).kind());
        assertTrue(events.get(0).payloadJson().contains("\"status\":\"FAILED\""));
    }

    @Test
    void failOrphaned_marks_only_running_rows_failed() {
        GateTask running = registry.register("review", "T-4", null);
        GateTask done = registry.register("review", "T-5", null);
        registry.update(new GateTask(done.id(), done.type(), done.ticketNo(), done.sessionId(),
                GateTaskStatus.SUCCEEDED, done.startedAt(), Instant.now(), "{}", null));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int changed = registry.failOrphaned(now);
        assertEquals(1, changed);
        GateTask recovered = registry.find(running.id()).orElseThrow();
        assertEquals(GateTaskStatus.FAILED, recovered.status());
        assertEquals(now, recovered.finishedAt());
        assertTrue(recovered.errorJson().contains("orphaned task"));
        assertEquals(GateTaskStatus.SUCCEEDED, registry.find(done.id()).orElseThrow().status());
    }
}
