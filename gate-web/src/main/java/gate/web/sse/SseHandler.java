package gate.web.sse;

import gate.domain.error.GateErrorCode;
import gate.ports.TaskRegistry;
import gate.web.util.Json;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.sse.SseClient;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Server-Sent Events writer for task progress with W3C Last-Event-ID cursor replay (Javalin).
 */
public final class SseHandler {

    static final int MAX_BUFFERED_EVENTS = 100;

    private final TaskRegistry tasks;

    public SseHandler(TaskRegistry tasks) {
        this.tasks = tasks;
    }

    public void handle(SseClient client, String taskId) {
        if (tasks.find(taskId).isEmpty()) {
            client.ctx().status(HttpStatus.NOT_FOUND);
            client.ctx().contentType("application/json; charset=utf-8");
            client.ctx().result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND", "no such task: " + taskId, null));
            return;
        }

        long lastEventId = 0;
        String lastEventHeader = client.ctx().header("Last-Event-ID");
        if (lastEventHeader != null && !lastEventHeader.isBlank()) {
            try {
                lastEventId = Long.parseLong(lastEventHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        BlockingQueue<TaskRegistry.GateTaskEvent> buffer = new ArrayBlockingQueue<>(MAX_BUFFERED_EVENTS);
        AtomicLong droppedEvents = new AtomicLong(0);
        AtomicLong lastFlushedSequence = new AtomicLong(lastEventId);

        try (Stream<TaskRegistry.GateTaskEvent> events = tasks.streamWithCursor(taskId, lastEventId)) {
            Thread producer = new Thread(() -> {
                try {
                    Iterator<TaskRegistry.GateTaskEvent> it = events.iterator();
                    while (it.hasNext()) {
                        TaskRegistry.GateTaskEvent e = it.next();
                        if (!buffer.offer(e)) {
                            TaskRegistry.GateTaskEvent discarded = buffer.poll();
                            if (discarded != null) {
                                droppedEvents.incrementAndGet();
                            }
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
                }
            }, "sse-producer-" + taskId);
            producer.setDaemon(true);
            producer.start();

            long eventSequence = lastEventId;
            boolean doneSeen = false;
            try {
                while (!doneSeen && !client.terminated()) {
                    TaskRegistry.GateTaskEvent e;
                    try {
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
                            client.sendComment("ping");
                        } catch (Exception io) {
                            break;
                        }
                        continue;
                    }
                    eventSequence++;
                    try {
                        client.sendEvent(e.kind(), e.payloadJson(), String.valueOf(eventSequence));
                        lastFlushedSequence.set(eventSequence);
                    } catch (Exception io) {
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
            }
        }
    }
}
