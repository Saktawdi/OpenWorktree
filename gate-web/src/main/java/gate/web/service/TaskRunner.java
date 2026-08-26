package gate.web.service;

import gate.application.GateService;
import gate.application.publish.PublishCommand;
import gate.application.publish.PublishResult;
import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.adapters.runtime.BoundedWorkDispatcher;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.infra.Clock;
import gate.ports.task.TaskRegistry;
import gate.ports.infra.TicketLockManager;
import gate.ports.task.WorkDispatcher;
import gate.web.util.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs long gate operations (review / publish) asynchronously behind a bounded dispatcher.
 *
 * <p>Each operation is registered in {@link TaskRegistry} first; the HTTP layer returns the task id
 * immediately (202). This runner updates progress, then persists a terminal {@code SUCCEEDED} or
 * {@code FAILED} task. The registry emits the SSE events.
 *
 * <p>引擎执行阶段（可能长达 engine.timeout_seconds）内部没有天然进度点：一个 3s 心跳把
 * 「已耗时 Ns」写进任务进度，前端轮询/SSE 都能看到任务活着而不是卡死。功能出入口
 * （start/done/failed + 耗时 + 判决）走 SLF4J，替代逐请求访问日志。
 */
public final class TaskRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);

    private final WorkDispatcher dispatcher;
    private final TaskRegistry tasks;
    private final GateService gateService;
    private final TicketLockManager ticketLocks;
    private final Clock clock;
    private final ScheduledExecutorService heartbeatPool;

    public TaskRunner(TaskRegistry tasks, GateService gateService, Clock clock, TicketLockManager ticketLocks) {
        this(tasks, gateService, clock, ticketLocks, new BoundedWorkDispatcher());
    }

    public TaskRunner(TaskRegistry tasks, GateService gateService, Clock clock,
               TicketLockManager ticketLocks, WorkDispatcher dispatcher) {
        this.tasks = tasks;
        this.gateService = gateService;
        this.clock = clock;
        this.ticketLocks = ticketLocks;
        this.dispatcher = dispatcher;
        this.heartbeatPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-task-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public String submitReview(String ticketNo, Integer round, Boolean humanPass, String note) {
        GateTask task = tasks.register("review", ticketNo, null);
        if (!dispatcher.trySubmit(() -> runReview(task, ticketNo, round, humanPass, note))) {
            fail(task, new GateException(GateErrorCode.GATE_ERROR_IO,
                    "review capacity exhausted; retry after workers drain"));
        }
        return task.id();
    }

    public String submitPublish(String ticketNo, Integer round) {
        GateTask task = tasks.register("publish", ticketNo, null);
        if (!dispatcher.trySubmit(() -> runPublish(task, ticketNo, round))) {
            fail(task, new GateException(GateErrorCode.GATE_ERROR_IO,
                    "publish capacity exhausted; retry after workers drain"));
        }
        return task.id();
    }

    public void close() {
        dispatcher.close();
        heartbeatPool.shutdownNow();
    }

    private void runReview(GateTask task, String ticketNo, Integer round, Boolean humanPass, String note) {
        String mode = humanPass == null ? "ai" : (humanPass ? "human-pass" : "human-reject");
        LOG.info("review.start ticket={} task={} mode={} round={}", ticketNo, task.id(), mode, round);
        long startMs = System.currentTimeMillis();
        AtomicBoolean done = new AtomicBoolean(false);
        ScheduledFuture<?> hb = startHeartbeat(task, done, startMs, "审查引擎执行中");
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
            LOG.info("review.done ticket={} task={} round={} verdict={} durationMs={} reason={}{}",
                    ticketNo, task.id(), r.reviewRound(), r.verdict(),
                    System.currentTimeMillis() - startMs,
                    r.reason(),
                    r.detail().isEmpty() ? "" : " detail=" + String.join(" | ", r.detail()));
        } catch (GateException e) {
            fail(task, e);
            LOG.warn("review.failed ticket={} task={} durationMs={} error={}",
                    ticketNo, task.id(), System.currentTimeMillis() - startMs, brief(e));
        } catch (Throwable e) {
            fail(task, e);
            LOG.error("review.failed ticket={} task={} durationMs={}",
                    ticketNo, task.id(), System.currentTimeMillis() - startMs, e);
        } finally {
            done.set(true);
            hb.cancel(false);
        }
    }

    private void runPublish(GateTask task, String ticketNo, Integer round) {
        LOG.info("publish.start ticket={} task={} round={}", ticketNo, task.id(), round);
        long startMs = System.currentTimeMillis();
        AtomicBoolean done = new AtomicBoolean(false);
        ScheduledFuture<?> hb = startHeartbeat(task, done, startMs, "发布执行中");
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
            // 工作区同步是 best-effort 尾步：null 表示未配置项目工作区或幂等重放，前端容忍缺省。
            body.put("workspace_sync_status", r.workspaceSyncStatus());
            body.put("workspace_sync_note", r.workspaceSyncNote());
            success(task, Json.write(body));
            LOG.info("publish.done ticket={} task={} commit={} durationMs={}",
                    ticketNo, task.id(), r.commitSha(), System.currentTimeMillis() - startMs);
        } catch (GateException e) {
            fail(task, e);
            LOG.warn("publish.failed ticket={} task={} durationMs={} error={}",
                    ticketNo, task.id(), System.currentTimeMillis() - startMs, brief(e));
        } catch (Throwable e) {
            fail(task, e);
            LOG.error("publish.failed ticket={} task={} durationMs={}",
                    ticketNo, task.id(), System.currentTimeMillis() - startMs, e);
        } finally {
            done.set(true);
            hb.cancel(false);
        }
    }

    /** 预期失败的摘要（类名 + message，截断）；意外异常直接带堆栈进 error 级别。 */
    private static String brief(GateException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().replace('\n', ' ');
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        return e.code().name() + ": " + msg;
    }

    /**
     * 引擎执行期心跳：每 3s 刷新「已耗时 Ns」。终态落盘后 fence 校验会拒绝迟到的进度写，
     * 这里捕获后置 done 结束心跳（避免调度线程刷堆栈）。
     */
    private ScheduledFuture<?> startHeartbeat(GateTask task, AtomicBoolean done, long startMs, String label) {
        return heartbeatPool.scheduleAtFixedRate(() -> {
            if (done.get()) {
                return;
            }
            long secs = (System.currentTimeMillis() - startMs) / 1000;
            try {
                progress(task, 30, label + " · 已耗时 " + secs + "s");
            } catch (Exception ignored) {
                done.set(true);
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    private void progress(GateTask task, int percent, String label) {
        // percent/label 必须落进 result_json：/api/tasks/{id} 轮询与 SSE progress 事件都从这里
        // 读取；此前构建后丢弃，前端在审查期间只能看到静态文案。
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("percent", percent);
        p.put("label", label);
        tasks.update(new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.RUNNING, task.startedAt(), null, Json.write(p), null));
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
