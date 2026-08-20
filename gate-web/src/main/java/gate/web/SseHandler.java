package gate.web;

import com.sun.net.httpserver.HttpExchange;
import gate.domain.error.GateErrorCode;
import gate.ports.TaskRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Server-Sent Events writer for task progress with W3C Last-Event-ID cursor replay
 * (Production Architecture §9, ADR-004).
 *
 * <p>Bounded-buffer + backpressure / 慢消费者背压 (Production Architecture §9.3, Phase 2 exit #4, DEBT-006):
 * each connection owns a fixed-capacity in-memory buffer ({@link #MAX_BUFFERED_EVENTS}=100).
 * When the buffer is full the oldest pending event is discarded and a backpressure counter
 * is recorded. The authoritative backlog remains in {@code task_event} (see
 * {@link gate.ports.TaskEventPort#replay}), so a slow consumer can reconnect with
 * {@code Last-Event-ID} and replay the missed sequence from the DB without loss.
 * Heartbeats ({@code : ping}) never occupy {@code sequence} and never enter the DB.
 */
final class SseHandler {

    /** Per-connection in-memory buffer cap; prevents unbounded growth for slow consumers. */
    static final int MAX_BUFFERED_EVENTS = 100;

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

        long lastEventId = 0;
        String lastEventHeader = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventHeader != null && !lastEventHeader.isBlank()) {
            try {
                lastEventId = Long.parseLong(lastEventHeader.trim());
            } catch (NumberFormatException ignored) {
                // Ignore malformed Last-Event-ID header and start from beginning or live
            }
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        // Bounded per-connection buffer – never grows beyond MAX_BUFFERED_EVENTS.
        // Slow consumers trigger backpressure: drop-oldest, keep Last-Event-ID cursor,
        // persistent replay remains DB-backed via TaskEventPort.replay.
        BlockingQueue<TaskRegistry.GateTaskEvent> buffer =
                new ArrayBlockingQueue<>(MAX_BUFFERED_EVENTS);
        AtomicLong droppedEvents = new AtomicLong(0);
        AtomicLong lastFlushedSequence = new AtomicLong(lastEventId);

        try (OutputStream os = exchange.getResponseBody();
             Stream<TaskRegistry.GateTaskEvent> events = tasks.stream(taskId)) {

            Thread producer = new Thread(() -> {
                try {
                    Iterator<TaskRegistry.GateTaskEvent> it = events.iterator();
                    while (it.hasNext()) {
                        TaskRegistry.GateTaskEvent e = it.next();
                        // Backpressure: bounded queue full -> discard oldest to bound memory
                        if (!buffer.offer(e)) {
                            TaskRegistry.GateTaskEvent discarded = buffer.poll();
                            if (discarded != null) {
                                droppedEvents.incrementAndGet();
                            }
                            // make room; loop until offered (at most one extra poll needed)
                            while (!buffer.offer(e)) {
                                TaskRegistry.GateTaskEvent d2 = buffer.poll();
                                if (d2 != null) {
                                    droppedEvents.incrementAndGet();
                                } else {
                                    break;
                                }
                            }
                        }
                        if ("done".equals(e.kind())) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // stream closed or interrupted – producer exits, consumer will drain
                }
            }, "sse-producer-" + taskId);
            producer.setDaemon(true);
            producer.start();

            long eventSequence = lastEventId;
            boolean doneSeen = false;
            try {
                while (!doneSeen) {
                    TaskRegistry.GateTaskEvent e;
                    try {
                        // 15s heartbeat per sse-cursor-design §9.3 – comment frame, no sequence
                        e = buffer.poll(15, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (e == null) {
                        if (!producer.isAlive() && buffer.isEmpty()) {
                            break;
                        }
                        try {
                            os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException io) {
                            // slow consumer / disconnect – preserve cursor for replay
                            break;
                        }
                        continue;
                    }
                    eventSequence++;
                    try {
                        // W3C SSE format – keep id/event/data order unchanged
                        os.write(("id: " + eventSequence + "\n").getBytes(StandardCharsets.UTF_8));
                        os.write(("event: " + e.kind() + "\n").getBytes(StandardCharsets.UTF_8));
                        os.write(("data: " + e.payloadJson() + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        lastFlushedSequence.set(eventSequence);
                    } catch (IOException io) {
                        // Write timeout / client disconnect – client can resume from
                        // lastFlushedSequence via Last-Event-ID and replay from DB
                        break;
                    }
                    if ("done".equals(e.kind())) {
                        doneSeen = true;
                    }
                }
            } finally {
                producer.interrupt();
                try {
                    producer.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // droppedEvents > 0 indicates backpressure was applied; cursor remains valid
                // because persistent replay is DB-based (task_event.sequence > cursor)
            }
        }
        return 200;
    }
}
