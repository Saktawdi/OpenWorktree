package gate.adapters.store;

import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.Clock;
import gate.ports.TaskRegistry;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-backed {@link TaskRegistry} storing task metadata in the {@code gate_task} table
 * (执行文档-后端-web §4.3, §6.1).
 *
 * <p>In-memory per-task channels give {@link #stream(String)} its replay-then-live, non-repeating,
 * finite event semantics. A task's events are appended to an ordered channel log and fanned out to
 * every concurrent subscriber's own bounded-free {@link BlockingQueue}; a terminal {@code done}
 * event is the last thing delivered, after which the stream ends. If the in-memory channel is
 * missing but the task is already {@code SUCCEEDED}/{@code FAILED} in the DB (e.g. the process
 * restarted), {@code stream} synthesizes a {@code done} event from the DB so callers always see a
 * terminal signal. Producers and subscribers never throw through the public surface.
 */
public final class JdbcGateTaskRepository implements TaskRegistry {

    /** Empty stream marker returned for an unknown {@code find()} id — guarantees a finite result. */
    private static final Stream<GateTaskEvent> NO_SUCH_TASK = Stream.empty();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConcurrentHashMap<String, TaskChannel> channels = new ConcurrentHashMap<>();

    private static final RowMapper<GateTask> MAPPER = (ResultSet rs, int n) -> new GateTask(
            rs.getString("id"),
            rs.getString("type"),
            rs.getString("ticket_no"),
            rs.getString("session_id"),
            GateTaskStatus.valueOf(rs.getString("status")),
            Instant.parse(rs.getString("started_at")),
            rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")),
            rs.getString("result_json"),
            rs.getString("error_json"));

    public JdbcGateTaskRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public GateTask register(String type, String ticketNo, String sessionId) {
        Instant now = clock.now();
        String id = UUID.randomUUID().toString();
        GateTask task = new GateTask(id, type, ticketNo, sessionId, GateTaskStatus.RUNNING,
                now, null, null, null);
        jdbc.update("""
                INSERT INTO gate_task(id, type, ticket_no, session_id, status, started_at, finished_at,
                                      result_json, error_json)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                task.id(), task.type(), task.ticketNo(), task.sessionId(),
                task.status().name(), task.startedAt().toString(), null, null, null);
        publish(new GateTaskEvent(id, "progress", taskPayload(task), now));
        return task;
    }

    @Override
    public void update(GateTask task) {
        // Idempotent persistence: never write the string "null" for a null result — leave the
        // column NULL so downstream readers see SQL NULL, not the literal text.
        jdbc.update("""
                UPDATE gate_task SET status = ?, finished_at = ?, result_json = ?, error_json = ?
                WHERE id = ?
                """,
                task.status().name(),
                task.finishedAt() == null ? null : task.finishedAt().toString(),
                task.resultJson(),
                task.errorJson(),
                task.id());
        Instant now = clock.now();
        if (task.isTerminal()) {
            publish(new GateTaskEvent(task.id(), "done", taskPayload(task), now));
        } else {
            publish(new GateTaskEvent(task.id(), "progress", taskPayload(task), now));
        }
    }

    @Override
    public Optional<GateTask> find(String id) {
        List<GateTask> rows = jdbc.query("SELECT * FROM gate_task WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long countByStatus(String status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gate_task WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    @Override
    public Stream<GateTaskEvent> stream(String id) {
        TaskChannel ch = channels.get(id);
        if (ch == null) {
            Optional<GateTask> existing = find(id);
            if (existing.isEmpty()) {
                return NO_SUCH_TASK;
            }
            GateTask t = existing.get();
            if (t.isTerminal()) {
                // In-memory events lost (e.g. after restart): synthesize a terminal done event from DB.
                Instant at = t.finishedAt() != null ? t.finishedAt() : t.startedAt();
                return Stream.of(new GateTaskEvent(t.id(), "done", taskPayload(t), at));
            }
            // RUNNING in DB but the channel is gone: create it and synthesize a progress heartbeat so
            // subscribers still see the current state before blocking for live events.
            ch = channels.computeIfAbsent(id, k -> new TaskChannel(k));
            publish(new GateTaskEvent(t.id(), "progress", taskPayload(t), clock.now()));
        }
        Subscriber sub = new Subscriber();
        synchronized (ch) {
            sub.replay.addAll(ch.recorded);
            ch.subscribers.add(sub);
            ch.active.incrementAndGet();
        }
        final TaskChannel channel = ch;
        final Subscriber subscriber = sub;
        Iterator<GateTaskEvent> it = new TaskIterator(channel, subscriber);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it,
                        Spliterator.ORDERED | Spliterator.NONNULL),
                false)
                .onClose(() -> closeSubscription(channel, subscriber));
    }

    /**
     * Startup reconcile (执行文档-后端-web §4.3): marks any orphaned {@code RUNNING} task — one with
     * no {@code finished_at} — as {@code FAILED}, stamping {@code finished_at} and an error payload.
     *
     * @return the number of rows marked {@code FAILED}.
     */
    public int failOrphaned(Instant now) {
        String errorJson = "{\"error_code\":21,\"error\":\"GATE_ERROR_IO\","
                + "\"message\":\"orphaned task marked FAILED at startup reconcile\"}";
        return jdbc.update("""
                UPDATE gate_task SET status = ?, finished_at = ?, error_json = ?
                WHERE status = ? AND finished_at IS NULL
                """,
                GateTaskStatus.FAILED.name(), now.toString(), errorJson,
                GateTaskStatus.RUNNING.name());
    }

    // -------------------------------------------------------------------------------------------
    // Event fan-out machinery
    // -------------------------------------------------------------------------------------------

    private void publish(GateTaskEvent event) {
        TaskChannel ch = channels.computeIfAbsent(event.taskId(), k -> new TaskChannel(k));
        synchronized (ch) {
            ch.recorded.add(event);
            for (Subscriber s : ch.subscribers) {
                s.live.add(event);
            }
            ch.done = ch.done || "done".equals(event.kind());
        }
        if (ch.done) {
            // A done event is the last publication for a task. If nobody is subscribed anymore the
            // channel can be dropped; live subscribers still drain their own queues.
            if (ch.active.get() == 0) {
                channels.remove(ch.id, ch);
            }
        }
    }

    private void closeSubscription(TaskChannel ch, Subscriber sub) {
        boolean removeChannel = false;
        synchronized (ch) {
            ch.subscribers.remove(sub);
            if (ch.active.decrementAndGet() == 0) {
                removeChannel = ch.done;
            }
        }
        if (removeChannel) {
            channels.remove(ch.id, ch);
        }
    }

    // -------------------------------------------------------------------------------------------
    // JSON payload builder + minimal writer (Map / List / String / Number / Boolean / null).
    // Mirrors gate.adapters.mcp.McpJsonRpc.serialize — no new dependency.
    // -------------------------------------------------------------------------------------------

    private static final String[] PAYLOAD_KEYS =
            {"id", "type", "ticket_no", "session_id", "status", "started_at", "finished_at",
                    "result_json", "error_json"};

    private static String taskPayload(GateTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(PAYLOAD_KEYS[0], t.id());
        m.put(PAYLOAD_KEYS[1], t.type());
        m.put(PAYLOAD_KEYS[2], t.ticketNo());
        m.put(PAYLOAD_KEYS[3], t.sessionId());
        m.put(PAYLOAD_KEYS[4], t.status().name());
        m.put(PAYLOAD_KEYS[5], t.startedAt().toString());
        m.put(PAYLOAD_KEYS[6], t.finishedAt() == null ? null : t.finishedAt().toString());
        m.put(PAYLOAD_KEYS[7], t.resultJson());
        m.put(PAYLOAD_KEYS[8], t.errorJson());
        return write(m);
    }

    private static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String raw) {
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // -------------------------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------------------------

    /** Per-task broadcast state. All mutable fields except {@code active} are guarded by the monitor. */
    private final class TaskChannel {
        final String id;
        final List<GateTaskEvent> recorded = new ArrayList<>();
        final Set<Subscriber> subscribers = new HashSet<>();
        final AtomicInteger active = new AtomicInteger();
        volatile boolean done;

        TaskChannel(String id) {
            this.id = id;
        }
    }

    /** One subscriber's stream state: its own replay snapshot + its own live queue. */
    private static final class Subscriber {
        final List<GateTaskEvent> replay = new ArrayList<>();
        final BlockingQueue<GateTaskEvent> live = new LinkedBlockingQueue<>();
    }

    /** Iterator backing the finite stream: replay snapshot first, then block on the live queue. */
    private final class TaskIterator implements Iterator<GateTaskEvent> {
        private final Subscriber sub;
        private final TaskChannel ch;
        private final Iterator<GateTaskEvent> replay;
        private GateTaskEvent current;
        private boolean finished;
        private boolean closed;

        TaskIterator(TaskChannel ch, Subscriber sub) {
            this.ch = ch;
            this.sub = sub;
            this.replay = sub.replay.iterator();
        }

        @Override
        public boolean hasNext() {
            if (finished) {
                return false;
            }
            if (replay.hasNext()) {
                current = replay.next();
                if ("done".equals(current.kind())) {
                    finish();
                }
                return true;
            }
            // Snapshot exhausted. Block for the next live event (the terminal done will arrive here),
            // or if the channel is already terminal and nothing else is pending, end.
            while (true) {
                if (ch.done && sub.live.isEmpty()) {
                    finish();
                    return false;
                }
                // take() is safe: while this subscriber is active the channel is never disposed, so a
                // terminal done — if/when published — is always enqueued to this subscriber's queue
                // and wakes this taker. Without done there is simply more live progress to wait for.
                GateTaskEvent e;
                try {
                    e = sub.live.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    finish();
                    return false;
                }
                current = e;
                if ("done".equals(e.kind())) {
                    finish();
                }
                return true;
            }
        }

        @Override
        public GateTaskEvent next() {
            if (current == null) {
                throw new java.util.NoSuchElementException();
            }
            GateTaskEvent e = current;
            current = null;
            return e;
        }

        private void finish() {
            if (!closed) {
                closed = true;
                finished = true;
                closeSubscription(ch, sub);
            }
        }
    }
}
