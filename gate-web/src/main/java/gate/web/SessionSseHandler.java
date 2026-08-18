package gate.web;

import com.sun.net.httpserver.HttpExchange;
import gate.domain.error.GateErrorCode;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.ToolCall;
import gate.ports.AgentSessionPort;
import gate.ports.SessionRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * SSE writer for agent session events (执行文档-后端-web §4.1, §9.3).
 *
 * <p>Emits initial message history snapshot, attaches a real-time listener for live SessionStreamChunks,
 * and flushes SSE data frames (`event: token | thinking | tool_call | usage | done | error`).
 */
final class SessionSseHandler {

    private final AgentSessionPort sessions;
    private final SessionRepository sessionRepository;

    SessionSseHandler(AgentSessionPort sessions, SessionRepository sessionRepository) {
        this.sessions = sessions;
        this.sessionRepository = sessionRepository;
    }

    int handle(HttpExchange exchange, String sessionId) throws IOException {
        if (sessionRepository.find(sessionId).isEmpty()) {
            Http.json(exchange, 404, Json.error(GateErrorCode.USAGE.code(),
                    "NOT_FOUND", "no such session: " + sessionId, null));
            return 404;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            // 1. Emit current history snapshot
            try (Stream<AgentSessionPort.SessionEvent> events = sessions.streamEvents(sessionId)) {
                Iterator<AgentSessionPort.SessionEvent> it = events.iterator();
                while (it.hasNext()) {
                    AgentSessionPort.SessionEvent e = it.next();
                    String data = Json.write(sessionEventJson(e));
                    os.write(("event: " + e.kind() + "\n").getBytes(StandardCharsets.UTF_8));
                    os.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            // 2. Attach live streaming listener
            CountDownLatch doneLatch = new CountDownLatch(1);
            try (AutoCloseable handle = sessions.attachListener(sessionId, chunk -> {
                try {
                    String eventName = "token";
                    if (chunk instanceof SessionStreamChunk.ThinkingChunk) {
                        eventName = "thinking";
                    } else if (chunk instanceof SessionStreamChunk.ToolCallChunk) {
                        eventName = "tool_call";
                    } else if (chunk instanceof SessionStreamChunk.UsageChunk) {
                        eventName = "usage";
                    } else if (chunk instanceof SessionStreamChunk.DoneChunk) {
                        eventName = "done";
                    } else if (chunk instanceof SessionStreamChunk.ErrorChunk) {
                        eventName = "error";
                    }
                    Map<String, Object> chunkPayload = chunkJson(chunk);
                    String data = Json.write(chunkPayload);
                    synchronized (os) {
                        os.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
                        os.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    if (chunk instanceof SessionStreamChunk.DoneChunk || chunk instanceof SessionStreamChunk.ErrorChunk) {
                        doneLatch.countDown();
                    }
                } catch (IOException ex) {
                    doneLatch.countDown();
                }
            })) {
                // Wait up to 5 seconds if live streaming or quick done
                doneLatch.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            // Final ping before closing
            os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException ignored) {
            // client disconnected
        }
        return 200;
    }

    private static Map<String, Object> chunkJson(SessionStreamChunk chunk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", chunk.sessionId());
        m.put("timestamp", chunk.timestamp().toString());
        if (chunk instanceof SessionStreamChunk.ContentChunk c) {
            m.put("text_delta", c.textDelta());
        } else if (chunk instanceof SessionStreamChunk.ThinkingChunk t) {
            m.put("thinking_delta", t.thinkingDelta());
        } else if (chunk instanceof SessionStreamChunk.ToolCallChunk tc) {
            m.put("call_id", tc.callId());
            m.put("tool_name", tc.toolName());
            m.put("argument_delta", tc.argumentDelta());
            m.put("result", tc.result());
            m.put("status", tc.status());
        } else if (chunk instanceof SessionStreamChunk.UsageChunk u) {
            if (u.usage() != null) {
                Map<String, Object> usageMap = new LinkedHashMap<>();
                usageMap.put("prompt_tokens", u.usage().promptTokens());
                usageMap.put("completion_tokens", u.usage().completionTokens());
                usageMap.put("total_tokens", u.usage().totalTokens());
                m.put("usage", usageMap);
            }
        } else if (chunk instanceof SessionStreamChunk.DoneChunk d) {
            m.put("full_message_id", d.fullMessageId());
        } else if (chunk instanceof SessionStreamChunk.ErrorChunk err) {
            m.put("error_code", err.errorCode());
            m.put("error_message", err.errorMessage());
        }
        return m;
    }

    private static Map<String, Object> sessionEventJson(AgentSessionPort.SessionEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", e.sessionId());
        m.put("kind", e.kind());
        m.put("message", messageJson(e.message()));
        return m;
    }

    private static Map<String, Object> messageJson(SessionMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id());
        m.put("session_id", msg.sessionId());
        m.put("role", msg.role().name());
        m.put("content", msg.content());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCall tc : msg.toolCalls()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", tc.name());
            cm.put("arguments_json", tc.argumentsJson());
            cm.put("result_json", tc.resultJson());
            calls.add(cm);
        }
        m.put("tool_calls", calls);
        if (msg.usage() == null) {
            m.put("usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", msg.usage().promptTokens());
            u.put("completion_tokens", msg.usage().completionTokens());
            u.put("total_tokens", msg.usage().totalTokens());
            m.put("usage", u);
        }
        m.put("degraded", msg.degraded());
        m.put("timestamp", msg.timestamp().toString());
        return m;
    }
}

