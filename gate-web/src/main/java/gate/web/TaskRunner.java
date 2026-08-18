package gate.web;

import gate.application.GateService;
import gate.application.PublishCommand;
import gate.application.PublishResult;
import gate.application.ReviewCommand;
import gate.application.ReviewResult;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.Clock;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs long gate operations (review / publish) asynchronously behind a single-thread executor
 * (执行文档-后端-web §4.3, §10 S2).
 *
 * <p>Each operation is registered in {@link TaskRegistry} first; the HTTP layer returns the task id
 * immediately (202). This runner updates progress, then persists a terminal {@code SUCCEEDED} or
 * {@code FAILED} task. The registry emits the SSE events.
 */
final class TaskRunner {

    private final ExecutorService executor;
    private final TaskRegistry tasks;
    private final GateService gateService;
    private final TicketLockManager ticketLocks;
    private final Clock clock;

    TaskRunner(TaskRegistry tasks, GateService gateService, Clock clock, TicketLockManager ticketLocks) {
        this.tasks = tasks;
        this.gateService = gateService;
        this.clock = clock;
        this.ticketLocks = ticketLocks;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gate-task-runner");
            t.setDaemon(true);
            return t;
        });
    }

    String submitReview(String ticketNo, Integer round, boolean humanPass, String note) {
        GateTask task = tasks.register("review", ticketNo, null);
        executor.submit(() -> runReview(task, ticketNo, round, humanPass, note));
        return task.id();
    }

    String submitPublish(String ticketNo, Integer round) {
        GateTask task = tasks.register("publish", ticketNo, null);
        executor.submit(() -> runPublish(task, ticketNo, round));
        return task.id();
    }

    void close() {
        executor.shutdown();
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
        tasks.update(new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.RUNNING, task.startedAt(), null, null, null));
    }

    private void success(GateTask task, String resultJson) {
        tasks.update(new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.SUCCEEDED, task.startedAt(), clock.now(), resultJson, null));
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
        tasks.update(new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.FAILED, task.startedAt(), clock.now(), null, Json.write(err)));
    }
}
