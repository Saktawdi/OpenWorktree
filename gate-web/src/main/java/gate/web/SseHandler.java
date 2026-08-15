package gate.web;

import com.sun.net.httpserver.HttpExchange;
import gate.domain.error.GateErrorCode;
import gate.ports.TaskRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Server-Sent Events writer for task progress (执行文档-后端-web §4.3, §9.3).
 *
 * <p>Each {@link TaskRegistry.GateTaskEvent} is written as a named SSE event:
 *
 * <pre>
 * event: &lt;kind&gt;
 * data: &lt;payloadJson&gt;
 *
 * </pre>
 *
 * The stream is flushed after every event. It ends after the task's {@code done} event, at which
 * point the exchange is closed by the caller.
 */
final class SseHandler {

    private final TaskRegistry tasks;

    SseHandler(TaskRegistry tasks) {
        this.tasks = tasks;
    }

    /**
     * Writes a task SSE stream to {@code exchange}.
     *
     * @return HTTP status already sent (200 on success, 404 if the task does not exist)
     */
    int handle(HttpExchange exchange, String taskId) throws IOException {
        if (tasks.find(taskId).isEmpty()) {
            Http.json(exchange, 404, Json.error(GateErrorCode.USAGE.code(),
                    "NOT_FOUND", "no such task: " + taskId, null));
            return 404;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody();
             Stream<TaskRegistry.GateTaskEvent> events = tasks.stream(taskId)) {
            Iterator<TaskRegistry.GateTaskEvent> it = events.iterator();
            while (it.hasNext()) {
                TaskRegistry.GateTaskEvent e = it.next();
                os.write(("event: " + e.kind() + "\n").getBytes(StandardCharsets.UTF_8));
                os.write(("data: " + e.payloadJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                if ("done".equals(e.kind())) {
                    break;
                }
            }
        }
        return 200;
    }
}
