package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.task.GateTask;
import gate.ports.TaskRegistry;
import gate.web.service.TaskRunner;
import gate.web.sse.SseHandler;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task Execution & SSE Controller.
 * Owns /api/tickets/{no}/review, /api/tickets/{no}/publish, /api/tasks/{id}, and /api/tasks/{id}/events routes.
 */
public final class TaskController implements WebController {

    private final TaskRegistry taskRegistry;
    private final TaskRunner taskRunner;
    private final SseHandler sseHandler;

    public TaskController(TaskRegistry taskRegistry, TaskRunner taskRunner) {
        this.taskRegistry = taskRegistry;
        this.taskRunner = taskRunner;
        this.sseHandler = new SseHandler(taskRegistry);
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/tickets/{ticketNo}/review", this::submitReview);
        app.post("/api/tickets/{ticketNo}/publish", this::submitPublish);
        app.get("/api/tasks/{id}", this::taskDetail);
        app.get("/api/tasks/{id}/events", this::taskEvents);
    }

    public void submitReview(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Map<String, Object> req = Json.parseObject(ctx.body());
        Integer round = null;
        if (req.containsKey("round") && req.get("round") != null) {
            round = Integer.parseInt(req.get("round").toString());
        }
        Boolean humanPass = req.get("human_pass") == null ? null
                : Boolean.parseBoolean(req.get("human_pass").toString());
        String note = req.get("note") == null ? null : req.get("note").toString();
        String taskId = taskRunner.submitReview(ticketNo, round, humanPass, note);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        ctx.status(HttpStatus.ACCEPTED);
        ctx.json(body);
    }

    public void submitPublish(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Map<String, Object> req = Json.parseObject(ctx.body());
        Integer round = null;
        if (req.containsKey("round") && req.get("round") != null) {
            round = Integer.parseInt(req.get("round").toString());
        }
        String taskId = taskRunner.submitPublish(ticketNo, round);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        ctx.status(HttpStatus.ACCEPTED);
        ctx.json(body);
    }

    public void taskDetail(Context ctx) {
        String id = ctx.pathParam("id");
        GateTask t = taskRegistry.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such task: " + id));
        ctx.status(HttpStatus.OK);
        ctx.json(taskJson(t));
    }

    public void taskEvents(Context ctx) {
        String id = ctx.pathParam("id");
        if (taskRegistry.find(id).isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND", "no such task: " + id, null));
            return;
        }
        startSse(ctx, client -> sseHandler.handle(client, id));
    }

    private static void startSse(Context ctx, java.util.function.Consumer<SseClient> consumer) {
        ctx.res().setStatus(200);
        ctx.res().setCharacterEncoding("UTF-8");
        ctx.res().setContentType("text/event-stream");
        ctx.res().addHeader("Connection", "close");
        ctx.res().addHeader("Cache-Control", "no-cache");
        ctx.res().addHeader("X-Accel-Buffering", "no");
        try {
            ctx.res().flushBuffer();
        } catch (IOException ignored) {
        }
        SseClient client = new SseClient(ctx);
        consumer.accept(client);
    }

    private static Map<String, Object> taskJson(GateTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("type", t.type());
        m.put("ticket_no", t.ticketNo());
        m.put("session_id", t.sessionId());
        m.put("status", t.status().name());
        m.put("started_at", t.startedAt().toString());
        m.put("finished_at", t.finishedAt() == null ? null : t.finishedAt().toString());
        m.put("result_json", t.resultJson());
        m.put("error_json", t.errorJson());
        return m;
    }
}
