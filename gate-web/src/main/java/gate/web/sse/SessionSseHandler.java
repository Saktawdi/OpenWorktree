package gate.web.sse;

import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.ToolCall;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.SessionRepository;
import gate.web.util.Json;
import io.javalin.http.sse.SseClient;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * SSE writer for agent session events (Javalin).
 */
public final class SessionSseHandler {

    static final long DEFAULT_HEARTBEAT_MILLIS = 15_000L;

    private final AgentSessionPort sessions;
    private final SessionRepository sessionRepository;
    private final long heartbeatMillis;

    public SessionSseHandler(AgentSessionPort sessions, SessionRepository sessionRepository) {
        this(sessions, sessionRepository, DEFAULT_HEARTBEAT_MILLIS);
    }

    public SessionSseHandler(AgentSessionPort sessions, SessionRepository sessionRepository, long heartbeatMillis) {
        this.sessions = sessions;
        this.sessionRepository = sessionRepository;
        this.heartbeatMillis = heartbeatMillis;
    }

    public void handle(SseClient client, String sessionId) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            client.close();
            return;
        }

        try {
            CountDownLatch doneLatch = new CountDownLatch(1);
            try (AutoCloseable handle = sessions.attachListener(sessionId, chunk -> {
                try {
                    if (client.terminated()) {
                        doneLatch.countDown();
                        return;
                    }
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
                    } else if (chunk instanceof SessionStreamChunk.PermissionAskedChunk) {
                        eventName = "permission_asked";
                    } else if (chunk instanceof SessionStreamChunk.PermissionRepliedChunk) {
                        eventName = "permission_replied";
                    } else if (chunk instanceof SessionStreamChunk.QuestionAskedChunk) {
                        eventName = "question_asked";
                    } else if (chunk instanceof SessionStreamChunk.QuestionRepliedChunk) {
                        eventName = "question_replied";
                    } else if (chunk instanceof SessionStreamChunk.TitleChunk) {
                        eventName = "session_title";
                    }
                    Map<String, Object> chunkPayload = chunkJson(chunk);
                    String data = Json.write(chunkPayload);
                    client.sendEvent(eventName, data);
                    if (chunk instanceof SessionStreamChunk.DoneChunk || chunk instanceof SessionStreamChunk.ErrorChunk) {
                        doneLatch.countDown();
                    }
                } catch (Exception ex) {
                    doneLatch.countDown();
                }
            })) {
                // 1. Emit current history snapshot
                try (Stream<AgentSessionPort.SessionEvent> events = sessions.streamEvents(sessionId)) {
                    Iterator<AgentSessionPort.SessionEvent> it = events.iterator();
                    while (it.hasNext() && !client.terminated()) {
                        AgentSessionPort.SessionEvent e = it.next();
                        String data = Json.write(sessionEventJson(e));
                        client.sendEvent(e.kind() != null ? e.kind() : "message", data);
                    }
                } catch (Exception ignored) {
                }

                while (!doneLatch.await(heartbeatMillis, TimeUnit.MILLISECONDS)) {
                    if (client.terminated() || doneLatch.getCount() == 0) {
                        break;
                    }
                    client.sendComment("ping");
                }
            } catch (Exception ignored) {
            }

            try {
                if (!client.terminated()) {
                    client.sendComment("ping");
                }
            } catch (Exception ignored) {
            }
        } finally {
            client.close();
        }
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
        } else if (chunk instanceof SessionStreamChunk.PermissionAskedChunk pa) {
            m.put("permission_id", pa.request().permissionId());
            m.put("permission", pa.request().permission());
            m.put("patterns", pa.request().patterns());
            m.put("always", pa.request().always());
            m.put("metadata", pa.request().metadata());
            m.put("message_id", pa.request().messageId());
            m.put("call_id", pa.request().callId());
        } else if (chunk instanceof SessionStreamChunk.PermissionRepliedChunk pr) {
            m.put("permission_id", pr.permissionId());
            m.put("response", pr.response());
            m.put("auto", pr.auto());
        } else if (chunk instanceof SessionStreamChunk.QuestionAskedChunk qa) {
            m.put("request_id", qa.request().requestId());
            m.put("questions", questionPromptsJson(qa.request()));
            m.put("message_id", qa.request().messageId());
            m.put("call_id", qa.request().callId());
        } else if (chunk instanceof SessionStreamChunk.QuestionRepliedChunk qr) {
            m.put("request_id", qr.requestId());
            m.put("rejected", qr.rejected());
            m.put("answers", qr.answers());
            m.put("auto", qr.auto());
        } else if (chunk instanceof SessionStreamChunk.TitleChunk t) {
            m.put("title", t.title());
        }
        return m;
    }

    private static List<Object> questionPromptsJson(gate.domain.session.QuestionRequest request) {
        List<Object> out = new ArrayList<>();
        for (gate.domain.session.QuestionRequest.QuestionPrompt p : request.questions()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("question", p.question());
            q.put("header", p.header());
            List<Object> options = new ArrayList<>();
            for (gate.domain.session.QuestionRequest.QuestionOption o : p.options()) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("label", o.label());
                om.put("description", o.description());
                options.add(om);
            }
            q.put("options", options);
            q.put("multiple", p.multiple());
            q.put("custom", p.custom());
            out.add(q);
        }
        return out;
    }

    private static Map<String, Object> sessionEventJson(AgentSessionPort.SessionEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", e.sessionId());
        m.put("kind", e.kind());
        m.put("message", messageJson(e.message()));
        return m;
    }

    private static Map<String, Object> messageJson(SessionMessage msg) {
        if (msg == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id());
        m.put("session_id", msg.sessionId());
        m.put("role", msg.role().name().toLowerCase(java.util.Locale.ROOT));
        m.put("content", msg.content());
        m.put("created_at", msg.timestamp().toString());
        List<Map<String, Object>> tcs = new ArrayList<>();
        for (ToolCall tc : msg.toolCalls()) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("tool_name", tc.name());
            tm.put("arguments", tc.argumentsJson());
            tm.put("result", tc.resultJson());
            tcs.add(tm);
        }
        m.put("tool_calls", tcs);
        return m;
    }
}
