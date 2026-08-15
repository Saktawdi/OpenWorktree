package gate.web;

import com.sun.net.httpserver.HttpExchange;
import gate.domain.error.GateErrorCode;
import gate.domain.session.SessionMessage;
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
import java.util.stream.Stream;

/**
 * SSE writer for agent session events (执行文档-后端-web §4.1, §9.3).
 *
 * <p>Currently the session adapter's {@code streamEvents} replays persisted messages; this handler
 * writes them as named SSE events ({@code event: message|usage|tool_call|done|error}).
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
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody();
             Stream<AgentSessionPort.SessionEvent> events = sessions.streamEvents(sessionId)) {
            Iterator<AgentSessionPort.SessionEvent> it = events.iterator();
            while (it.hasNext()) {
                AgentSessionPort.SessionEvent e = it.next();
                String data = Json.write(sessionEventJson(e));
                os.write(("event: " + e.kind() + "\n").getBytes(StandardCharsets.UTF_8));
                os.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
        return 200;
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
