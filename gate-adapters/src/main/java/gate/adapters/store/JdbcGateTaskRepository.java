package gate.adapters.store;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.Clock;
import gate.ports.OutboxPort;
import gate.ports.TaskClaimPort;
import gate.ports.TaskEventPort;
import gate.ports.TaskRegistry;
import java.sql.ResultSet;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcGateTaskRepository implements TaskRegistry, TaskEventPort, OutboxPort, TaskClaimPort {
    private static final Stream<GateTaskEvent> NO_SUCH_TASK = Stream.empty();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConcurrentHashMap<String, TaskChannel> channels = new ConcurrentHashMap<>();
    private final AtomicLong staleRejections = new AtomicLong();
    private static final RowMapper<GateTask> MAPPER = (ResultSet rs, int n) -> new GateTask(
            rs.getString("id"), rs.getString("type"), rs.getString("ticket_no"), rs.getString("session_id"),
            GateTaskStatus.valueOf(rs.getString("status")), Instant.parse(rs.getString("started_at")),
            rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")),
            rs.getString("result_json"), rs.getString("error_json"),
            rs.getString("tenant_id") == null ? "default" : rs.getString("tenant_id"),
            rs.getString("project_id"), rs.getString("idempotency_key"), rs.getString("request_digest"),
            rs.getInt("priority"),
            rs.getString("available_at") == null ? null : Instant.parse(rs.getString("available_at")),
            rs.getString("lease_owner"), rs.getString("lease_until") == null ? null : Instant.parse(rs.getString("lease_until")),
            rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getLong("fence_token"), rs.getLong("next_event_sequence"),
            rs.getString("timeout_at") == null ? null : Instant.parse(rs.getString("timeout_at")),
            rs.getString("cancel_requested_at") == null ? null : Instant.parse(rs.getString("cancel_requested_at")),
            rs.getString("result_ref"), rs.getString("error_code"));
    private static final RowMapper<TaskEventPort.TaskEvent> EVENT_MAPPER = (ResultSet rs, int n) -> new TaskEventPort.TaskEvent(
            rs.getString("event_id"), rs.getString("task_id"), rs.getLong("sequence"),
            rs.getString("event_type"), rs.getString("payload_json"), Instant.parse(rs.getString("created_at")),
            rs.getString("expires_at") == null ? null : Instant.parse(rs.getString("expires_at")));
    private static final RowMapper<OutboxPort.OutboxEntry> OUTBOX_MAPPER = (ResultSet rs, int n) -> new OutboxPort.OutboxEntry(
            rs.getString("outbox_id"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
            rs.getString("event_type"), rs.getString("payload_json"), Instant.parse(rs.getString("created_at")));
    public JdbcGateTaskRepository(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Override public GateTask register(String type, String ticketNo, String sessionId) { return registerWithKey(type, ticketNo, sessionId, null, null); }
    public GateTask registerWithKey(String type, String ticketNo, String sessionId, String idempotencyKey, String requestDigest) {
        if (idempotencyKey != null) {
            Optional<GateTask> existing = findByIdempotency("default", idempotencyKey);
            if (existing.isPresent()) {
                GateTask ex = existing.get();
                if (requestDigest != null && ex.requestDigest() != null && !requestDigest.equals(ex.requestDigest())) {
                    throw new GateException(GateErrorCode.GATE_ERROR_IO, "IDEMPOTENCY_CONFLICT: request_digest mismatch for key " + idempotencyKey);
                }
                return ex;
            }
        }
        Instant now = clock.now();
        String id = UUID.randomUUID().toString();
        GateTask task = new GateTask(id, type, ticketNo, sessionId, GateTaskStatus.RUNNING, now, null, null, null,
                "default", null, idempotencyKey, requestDigest, 0, now, null, null, 1, 3, 1L, 0L, null, null, null, null);
        try {
            jdbc.update("INSERT INTO gate_task(id, type, ticket_no, session_id, status, started_at, finished_at, result_json, error_json, tenant_id, project_id, idempotency_key, request_digest, priority, available_at, lease_owner, lease_until, attempt, max_attempts, fence_token, next_event_sequence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    task.id(), task.type(), task.ticketNo(), task.sessionId(), task.status().name(), task.startedAt().toString(), null, null, null,
                    task.tenantId(), task.projectId(), task.idempotencyKey(), task.requestDigest(), task.priority(), task.availableAt().toString(),
                    task.leaseOwner(), task.leaseUntil() == null ? null : task.leaseUntil().toString(), task.attempt(), task.maxAttempts(), task.fenceToken(), task.nextEventSequence());
        } catch (DuplicateKeyException e) {
            Optional<GateTask> dup = findByIdempotency("default", idempotencyKey);
            if (dup.isPresent()) return dup.get();
            throw e;
        }
        append(task.id(), "progress", taskPayload(task));
        appendOutbox("task", task.id(), "task.created", taskPayload(task));
        publish(new GateTaskEvent(id, "progress", taskPayload(task), now));
        return task;
    }
    @Override public void update(GateTask task) { updateWithFence(task, task.fenceToken()); }
    public void updateWithFence(GateTask task, long currentFenceToken) {
        int updated = jdbc.update("UPDATE gate_task SET status = ?, finished_at = ?, result_json = ?, error_json = ? WHERE id = ? AND fence_token = ? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                task.status().name(), task.finishedAt() == null ? null : task.finishedAt().toString(), task.resultJson(), task.errorJson(), task.id(), currentFenceToken);
        if (updated == 0) {
            staleRejections.incrementAndGet();
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "Task update rejected due to stale fence token or terminal state. taskId=" + task.id());
        }
        Instant now = clock.now();
        String eventType = task.isTerminal() ? "done" : "progress";
        append(task.id(), eventType, taskPayload(task));
        appendOutbox("task", task.id(), "task." + eventType, taskPayload(task));
        publish(new GateTaskEvent(task.id(), eventType, taskPayload(task), now));
    }
    @Override public Optional<GateTask> find(String id) {
        List<GateTask> rows = jdbc.query("SELECT * FROM gate_task WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
    public Optional<GateTask> findByIdempotency(String tenantId, String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        List<GateTask> rows = jdbc.query("SELECT * FROM gate_task WHERE tenant_id = ? AND idempotency_key = ?", MAPPER, tenantId, idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
    @Override public long countByStatus(String status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gate_task WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }
    @Override public Stream<GateTaskEvent> stream(String id) { return streamWithCursor(id, 0); }
    public Stream<GateTaskEvent> streamWithCursor(String id, long afterSequence) {
        Optional<GateTask> existing = find(id);
        if (existing.isEmpty()) return NO_SUCH_TASK;
        List<TaskEventPort.TaskEvent> historicalEvents = replay(id, afterSequence);
        TaskChannel ch = channels.computeIfAbsent(id, k -> new TaskChannel(k));
        Subscriber sub = new Subscriber();
        synchronized (ch) {
            for (TaskEventPort.TaskEvent he : historicalEvents) sub.replay.add(new GateTaskEvent(he.taskId(), he.eventType(), he.payloadJson(), he.createdAt()));
            ch.subscribers.add(sub);
            ch.active.incrementAndGet();
        }
        final TaskChannel channel = ch; final Subscriber subscriber = sub;
        Iterator<GateTaskEvent> it = new TaskIterator(channel, subscriber);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL), false).onClose(() -> closeSubscription(channel, subscriber));
    }
    public int failOrphaned(Instant now) {
        String errorJson = "{\"error_code\":21,\"error\":\"GATE_ERROR_IO\",\"message\":\"orphaned task marked FAILED at startup reconcile\"}";
        return jdbc.update("UPDATE gate_task SET status = ?, finished_at = ?, error_json = ? WHERE status = ? AND finished_at IS NULL", GateTaskStatus.FAILED.name(), now.toString(), errorJson, GateTaskStatus.RUNNING.name());
    }
    @Override public GateTask enqueue(String type, String ticketNo, String sessionId, String tenantId, String projectId, String idempotencyKey, String requestDigest, int priority, Instant availableAt) {
        if (idempotencyKey != null) {
            String tid = tenantId == null ? "default" : tenantId;
            Optional<GateTask> existing = findByIdempotency(tid, idempotencyKey);
            if (existing.isPresent()) {
                GateTask ex = existing.get();
                if (requestDigest != null && ex.requestDigest() != null && !requestDigest.equals(ex.requestDigest())) throw new GateException(GateErrorCode.GATE_ERROR_IO, "IDEMPOTENCY_CONFLICT");
                return ex;
            }
        }
        Instant now = clock.now();
        Instant avail = availableAt == null ? now : availableAt;
        String id = UUID.randomUUID().toString();
        GateTask task = new GateTask(id, type, ticketNo, sessionId, GateTaskStatus.QUEUED, now, null, null, null, tenantId == null ? "default" : tenantId, projectId, idempotencyKey, requestDigest, priority, avail, null, null, 0, 3, 0L, 0L, null, null, null, null);
        try {
            jdbc.update("INSERT INTO gate_task(id, type, ticket_no, session_id, status, started_at, finished_at, result_json, error_json, tenant_id, project_id, idempotency_key, request_digest, priority, available_at, lease_owner, lease_until, attempt, max_attempts, fence_token, next_event_sequence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    task.id(), task.type(), task.ticketNo(), task.sessionId(), task.status().name(), task.startedAt().toString(), null, null, null, task.tenantId(), task.projectId(), task.idempotencyKey(), task.requestDigest(), task.priority(), task.availableAt().toString(), null, null, task.attempt(), task.maxAttempts(), task.fenceToken(), task.nextEventSequence());
        } catch (DuplicateKeyException e) {
            Optional<GateTask> dup = findByIdempotency(task.tenantId(), idempotencyKey);
            if (dup.isPresent()) return dup.get();
            throw e;
        }
        append(task.id(), "progress", taskPayload(task));
        appendOutbox("task", task.id(), "task.created", taskPayload(task));
        return task;
    }
    @Override public Optional<GateTask> claimNext(String workerId, Duration lease, Instant now) {
        String nowStr = now.toString();
        String leaseUntilStr = now.plus(lease).toString();
        List<String> candidates = jdbc.query("SELECT id FROM gate_task WHERE status IN ('QUEUED','RETRY_WAIT') AND available_at <= ? ORDER BY priority DESC, available_at ASC, id ASC LIMIT 1", (rs,n) -> rs.getString("id"), nowStr);
        if (candidates.isEmpty()) return Optional.empty();
        String candidateId = candidates.get(0);
        int updated = jdbc.update("UPDATE gate_task SET status = ?, lease_owner = ?, lease_until = ?, attempt = attempt + 1, fence_token = fence_token + 1 WHERE id = ? AND status IN ('QUEUED','RETRY_WAIT')", GateTaskStatus.RUNNING.name(), workerId, leaseUntilStr, candidateId);
        if (updated == 0) return Optional.empty();
        Optional<GateTask> claimed = find(candidateId);
        claimed.ifPresent(t -> {
            append(t.id(), "progress", taskPayload(t));
            appendOutbox("task", t.id(), "task.claimed", taskPayload(t));
            publish(new GateTaskEvent(t.id(), "progress", taskPayload(t), now));
        });
        return claimed;
    }
    @Override public boolean renewLease(String taskId, String workerId, long fenceToken, Duration lease, Instant now) {
        String leaseUntilStr = now.plus(lease).toString();
        int updated = jdbc.update("UPDATE gate_task SET lease_until = ? WHERE id = ? AND lease_owner = ? AND fence_token = ? AND status = ?", leaseUntilStr, taskId, workerId, fenceToken, GateTaskStatus.RUNNING.name());
        return updated == 1;
    }
    @Override public int reapExpiredLeases(Instant now) {
        String nowStr = now.toString();
        int retried = jdbc.update("UPDATE gate_task SET status = ?, available_at = ?, lease_owner = NULL, lease_until = NULL WHERE status = ? AND lease_until IS NOT NULL AND lease_until <= ? AND attempt < max_attempts", GateTaskStatus.RETRY_WAIT.name(), nowStr, GateTaskStatus.RUNNING.name(), nowStr);
        int failed = jdbc.update("UPDATE gate_task SET status = ?, finished_at = ?, lease_owner = NULL, lease_until = NULL WHERE status = ? AND lease_until IS NOT NULL AND lease_until <= ? AND attempt >= max_attempts", GateTaskStatus.FAILED.name(), nowStr, GateTaskStatus.RUNNING.name(), nowStr);
        return retried + failed;
    }
    @Override public long countStaleRejections() { return staleRejections.get(); }
    @Override public TaskEventPort.TaskEvent append(String taskId, String eventType, String payloadJson) {
        synchronized (taskId.intern()) {
            Instant now = clock.now();
            String eventId = UUID.randomUUID().toString();
            jdbc.update("UPDATE gate_task SET next_event_sequence = next_event_sequence + 1 WHERE id = ?", taskId);
            Long seq = jdbc.queryForObject("SELECT next_event_sequence FROM gate_task WHERE id = ?", Long.class, taskId);
            long sequence = (seq == null) ? 1L : seq;
            jdbc.update("INSERT INTO task_event(event_id, task_id, sequence, event_type, payload_json, created_at) VALUES (?,?,?,?,?,?)", eventId, taskId, sequence, eventType, payloadJson, now.toString());
            return new TaskEventPort.TaskEvent(eventId, taskId, sequence, eventType, payloadJson, now, null);
        }
    }
    @Override public List<TaskEventPort.TaskEvent> replay(String taskId, long afterSequence) {
        return jdbc.query("SELECT * FROM task_event WHERE task_id = ? AND sequence > ? ORDER BY sequence ASC", EVENT_MAPPER, taskId, afterSequence);
    }
    @Override public long latestSequence(String taskId) {
        Long seq = jdbc.queryForObject("SELECT MAX(sequence) FROM task_event WHERE task_id = ?", Long.class, taskId);
        return seq == null ? 0L : seq;
    }
    @Override public void append(String aggregateType, String aggregateId, String eventType, String payloadJson) { appendOutbox(aggregateType, aggregateId, eventType, payloadJson); }
    private void appendOutbox(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        Instant now = clock.now();
        String outboxId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO outbox(outbox_id, aggregate_type, aggregate_id, event_type, payload_json, created_at, available_at) VALUES (?,?,?,?,?,?,?)", outboxId, aggregateType, aggregateId, eventType, payloadJson, now.toString(), now.toString());
    }
    @Override public int relay(int limit) {
        Instant now = clock.now();
        List<OutboxPort.OutboxEntry> pending = pending(limit);
        for (OutboxPort.OutboxEntry entry : pending) jdbc.update("UPDATE outbox SET relayed_at = ? WHERE outbox_id = ?", now.toString(), entry.outboxId());
        return pending.size();
    }
    @Override public List<OutboxPort.OutboxEntry> pending(int limit) {
        return jdbc.query("SELECT * FROM outbox WHERE relayed_at IS NULL ORDER BY created_at ASC LIMIT ?", OUTBOX_MAPPER, limit);
    }
    private void publish(GateTaskEvent event) {
        TaskChannel ch = channels.computeIfAbsent(event.taskId(), k -> new TaskChannel(k));
        synchronized (ch) {
            ch.recorded.add(event);
            for (Subscriber s : ch.subscribers) s.live.offer(event);
            ch.done = ch.done || "done".equals(event.kind());
        }
        if (ch.done && ch.active.get() == 0) channels.remove(ch.id, ch);
    }
    private void closeSubscription(TaskChannel ch, Subscriber sub) {
        boolean removeChannel = false;
        synchronized (ch) {
            ch.subscribers.remove(sub);
            if (ch.active.decrementAndGet() == 0) removeChannel = ch.done;
        }
        if (removeChannel) channels.remove(ch.id, ch);
    }
    private static final String[] PAYLOAD_KEYS = {"id", "type", "ticket_no", "session_id", "status", "started_at", "finished_at", "result_json", "error_json"};
    private static String taskPayload(GateTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(PAYLOAD_KEYS[0], t.id()); m.put(PAYLOAD_KEYS[1], t.type()); m.put(PAYLOAD_KEYS[2], t.ticketNo());
        m.put(PAYLOAD_KEYS[3], t.sessionId()); m.put(PAYLOAD_KEYS[4], t.status().name());
        m.put(PAYLOAD_KEYS[5], t.startedAt().toString()); m.put(PAYLOAD_KEYS[6], t.finishedAt() == null ? null : t.finishedAt().toString());
        m.put(PAYLOAD_KEYS[7], t.resultJson()); m.put(PAYLOAD_KEYS[8], t.errorJson());
        return write(m);
    }
    private static String write(Object value) { StringBuilder sb = new StringBuilder(); writeValue(sb, value); return sb.toString(); }
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b);
        else if (v instanceof Number n) sb.append(n.toString());
        else if (v instanceof Map<?, ?> m) {
            sb.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(','); first=false; writeString(sb, String.valueOf(e.getKey())); sb.append(':'); writeValue(sb, e.getValue());
            } sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('['); boolean first = true;
            for (Object item : list) { if (!first) sb.append(','); first=false; writeValue(sb, item); } sb.append(']');
        } else if (v instanceof String s) writeString(sb, s);
        else writeString(sb, String.valueOf(v));
    }
    private static void writeString(StringBuilder sb, String raw) {
        sb.append('"'); for (int i=0;i<raw.length();i++) { char c=raw.charAt(i); switch(c){ case '"' -> sb.append("\\\""); case '\\' -> sb.append("\\\\"); case '\n' -> sb.append("\\n"); case '\r' -> sb.append("\\r"); case '\t' -> sb.append("\\t"); default -> { if(c<0x20) sb.append(String.format("\\u%04x",(int)c)); else sb.append(c);} } } sb.append('"');
    }
    private final class TaskChannel { final String id; final List<GateTaskEvent> recorded = new ArrayList<>(); final Set<Subscriber> subscribers = new HashSet<>(); final AtomicInteger active = new AtomicInteger(); volatile boolean done; TaskChannel(String id){this.id=id;} }
    private static final class Subscriber { final List<GateTaskEvent> replay = new ArrayList<>(); final BlockingQueue<GateTaskEvent> live = new LinkedBlockingQueue<>(1000); }
    private final class TaskIterator implements Iterator<GateTaskEvent> {
        private final Subscriber sub; private final TaskChannel ch; private final Iterator<GateTaskEvent> replay; private GateTaskEvent current; private boolean finished; private boolean closed;
        TaskIterator(TaskChannel ch, Subscriber sub){ this.ch=ch; this.sub=sub; this.replay=sub.replay.iterator(); }
        @Override public boolean hasNext() {
            if(finished) return false;
            if(replay.hasNext()){ current=replay.next(); if("done".equals(current.kind())) finish(); return true; }
            while(true){
                if(ch.done && sub.live.isEmpty()){ finish(); return false; }
                GateTaskEvent e; try{ e=sub.live.take(); } catch(InterruptedException ex){ Thread.currentThread().interrupt(); finish(); return false; }
                current=e; if("done".equals(e.kind())) finish(); return true;
            }
        }
        @Override public GateTaskEvent next(){ if(current==null) throw new java.util.NoSuchElementException(); GateTaskEvent e=current; current=null; return e; }
        private void finish(){ if(!closed){ closed=true; finished=true; closeSubscription(ch, sub); } }
    }
}
