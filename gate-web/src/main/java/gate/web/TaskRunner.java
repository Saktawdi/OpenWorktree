package gate.web;

import gate.application.GateService;
import gate.application.PublishCommand;
import gate.application.PublishResult;
import gate.application.ReviewCommand;
import gate.application.ReviewResult;
import gate.adapters.runtime.BoundedWorkDispatcher;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.Clock;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import gate.ports.WorkDispatcher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs long gate operations (review / publish) asynchronously behind a bounded dispatcher.
 *
 * <p>Each operation is registered in {@link TaskRegistry} first; the HTTP layer returns the task id
 * immediately (202). This runner updates progress, then persists a terminal {@code SUCCEEDED} or
 * {@code FAILED} task. The registry emits the SSE events.
 */
final class TaskRunner {

    private final WorkDispatcher dispatcher;
    private final TaskRegistry tasks;
    private final GateService gateService;
    private final TicketLockManager ticketLocks;
    private final Clock clock;

    TaskRunner(TaskRegistry tasks, GateService gateService, Clock clock, TicketLockManager ticketLocks) {
        this.tasks = tasks;
        this.gateService = gateService;
        this.clock = clock;
        this.ticketLocks = ticketLocks;
        this.dispatcher = new BoundedWorkDispatcher();
    }

    TaskRunner(TaskRegistry tasks, GateService gateService, Clock clock,
               TicketLockManager ticketLocks, WorkDispatcher dispatcher) {
        this.tasks = tasks;
        this.gateService = gateService;
        this.clock = clock;
        this.ticketLocks = ticketLocks;
        this.dispatcher = dispatcher;
    }

    String submitReview(String ticketNo, Integer round, boolean humanPass, String note) {
        var ctx = gate.ports.security.SecurityContextHolder.get();
        String tenant = ctx != null && ctx.tenantId() != null ? ctx.tenantId() : "default";
        String creator = ctx != null ? ctx.userId() : null;
        String noteHash = note == null ? "null" : Integer.toHexString(note.hashCode());
        String idempotencyKey = "review:" + tenant + ":" + ticketNo + ":" + round + ":" + humanPass + ":" + noteHash;
        String requestDigest = noteHash;
        GateTask task = tasks.registerWithKey("review", ticketNo, null, idempotencyKey, requestDigest);
        tasks.setCreator(task.id(), creator);
        GateTask taskFinal = task;
        if (!dispatcher.trySubmit(() -> {
            String effectiveCreator = creator;
            try {
                String dbCreator = tasks.findCreator(taskFinal.id());
                if (dbCreator != null) effectiveCreator = dbCreator;
            } catch (Exception ignored) {}
            gate.ports.security.SecurityContextHolder.set(new gate.domain.security.SecurityContext(effectiveCreator, tenant, null, ctx != null ? ctx.roles() : Set.of(), ctx != null ? ctx.tokenHash() : null));
            try { runReview(taskFinal, ticketNo, round, humanPass, note); } finally { gate.ports.security.SecurityContextHolder.clear(); }
        })) {
            fail(task, new GateException(GateErrorCode.GATE_ERROR_IO,
                    "review capacity exhausted; retry after workers drain"));
        }
        return task.id();
    }

    String submitPublish(String ticketNo, Integer round) {
        var ctx = gate.ports.security.SecurityContextHolder.get();
        String tenant = ctx != null && ctx.tenantId() != null ? ctx.tenantId() : "default";
        String creator = ctx != null ? ctx.userId() : null;
        String baseKey = "publish:" + tenant + ":" + ticketNo + ":" + round;
        String requestDigest = String.valueOf(round);
        GateTask existing = null;
        try { existing = tasks.findByIdempotency(tenant, baseKey).orElse(null); } catch (Exception ignored) {}
        String idempotencyKey = baseKey;
        if (existing != null && existing.isTerminal() && existing.status() == GateTaskStatus.FAILED) {
            idempotencyKey = baseKey + ":retry:" + (existing.attempt() + 1);
            requestDigest = requestDigest + ":retry:" + (existing.attempt() + 1);
        }
        GateTask task = tasks.registerWithKey("publish", ticketNo, null, idempotencyKey, requestDigest);
        tasks.setCreator(task.id(), creator);
        GateTask taskFinal = task;
        if (!dispatcher.trySubmit(() -> {
            String effectiveCreator = creator;
            try {
                String dbCreator = tasks.findCreator(taskFinal.id());
                if (dbCreator != null) effectiveCreator = dbCreator;
            } catch (Exception ignored) {}
            gate.ports.security.SecurityContextHolder.set(new gate.domain.security.SecurityContext(effectiveCreator, tenant, null, ctx != null ? ctx.roles() : Set.of(), ctx != null ? ctx.tokenHash() : null));
            try { runPublish(taskFinal, ticketNo, round); } finally { gate.ports.security.SecurityContextHolder.clear(); }
        })) {
            fail(task, new GateException(GateErrorCode.GATE_ERROR_IO,
                    "publish capacity exhausted; retry after workers drain"));
        }
        return task.id();
    }

    void close() {
        dispatcher.close();
    }

    private void runReview(GateTask task, String ticketNo, Integer round, boolean humanPass, String note) {
        try {
            progress(task, 10, "准备审核");
            ReviewResult r = gateService.review(new ReviewCommand(ticketNo, round, humanPass, note));
            progress(task, 90, "审核完成，正在落盘");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticket_no", r.ticketNo());
            body.put("review_round", r.reviewRound());
            body.put("tree_hash", r.treeHash());
            body.put("dangling_commit", r.danglingCommit());
            body.put("verdict", r.verdict().name());
            body.put("reason", r.reason());
            body.put("detail", r.detail());
            success(task, Json.write(body));
        } catch (Throwable e) {
            fail(task, e);
        }
    }

    private void runPublish(GateTask task, String ticketNo, Integer round) {
        try (AutoCloseable ignored = ticketLocks.acquire(ticketNo)) {
            progress(task, 10, "准备发布");
            PublishResult r = gateService.publish(new PublishCommand(ticketNo, round));
            progress(task, 90, "发布成功，正在确认");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticket_no", r.ticketNo());
            body.put("review_round", r.reviewRound());
            body.put("tree_hash", r.treeHash());
            body.put("commit_sha", r.commitSha());
            body.put("target_ref", r.targetRef());
            body.put("ref_before", r.refBefore());
            body.put("ref_after", r.refAfter());
            body.put("already_published", r.alreadyPublished());
            success(task, Json.write(body));
        } catch (Throwable e) {
            fail(task, e);
        }
    }

    private void progress(GateTask task, int percent, String label) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("percent", percent);
        p.put("label", label);
        var latest = tasks.find(task.id()).orElse(task);
        GateTask toUpdate = new GateTask(latest.id(), latest.type(), latest.ticketNo(), latest.sessionId(),
                GateTaskStatus.RUNNING, latest.startedAt(), null, null, null,
                latest.tenantId(), latest.projectId(), latest.idempotencyKey(), latest.requestDigest(),
                latest.priority(), latest.availableAt(), latest.leaseOwner(), latest.leaseUntil(),
                latest.attempt(), latest.maxAttempts(), latest.fenceToken(), latest.nextEventSequence(),
                latest.timeoutAt(), latest.cancelRequestedAt(), latest.resultRef(), latest.errorCode());
        tasks.update(toUpdate);
    }

    private void success(GateTask task, String resultJson) {
        var latest = tasks.find(task.id()).orElse(task);
        GateTask toUpdate = new GateTask(latest.id(), latest.type(), latest.ticketNo(), latest.sessionId(),
                GateTaskStatus.SUCCEEDED, latest.startedAt(), clock.now(), resultJson, null,
                latest.tenantId(), latest.projectId(), latest.idempotencyKey(), latest.requestDigest(),
                latest.priority(), latest.availableAt(), latest.leaseOwner(), latest.leaseUntil(),
                latest.attempt(), latest.maxAttempts(), latest.fenceToken(), latest.nextEventSequence(),
                latest.timeoutAt(), latest.cancelRequestedAt(), latest.resultRef(), latest.errorCode());
        tasks.update(toUpdate);
    }

    private void fail(GateTask task, Throwable e) {
        int code;
        String name;
        String message;
        if (e instanceof GateException ge) {
            code = ge.code().code();
            name = ge.code().name();
            message = ge.getMessage();
        } else {
            code = GateErrorCode.INTERNAL.code();
            name = GateErrorCode.INTERNAL.name();
            message = "internal error";
        }
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error_code", code);
        err.put("error", name);
        err.put("message", message == null ? "" : message);
        err.put("detail", List.of());
        var latest = tasks.find(task.id()).orElse(task);
        GateTask toUpdate = new GateTask(latest.id(), latest.type(), latest.ticketNo(), latest.sessionId(),
                GateTaskStatus.FAILED, latest.startedAt(), clock.now(), null, Json.write(err),
                latest.tenantId(), latest.projectId(), latest.idempotencyKey(), latest.requestDigest(),
                latest.priority(), latest.availableAt(), latest.leaseOwner(), latest.leaseUntil(),
                latest.attempt(), latest.maxAttempts(), latest.fenceToken(), latest.nextEventSequence(),
                latest.timeoutAt(), latest.cancelRequestedAt(), latest.resultRef(), latest.errorCode());
        tasks.update(toUpdate);
    }
}
