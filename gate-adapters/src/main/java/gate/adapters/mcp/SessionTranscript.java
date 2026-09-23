package gate.adapters.mcp;

import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.ToolCall;
import gate.domain.session.TurnPart;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a session transcript for the {@code session_read} MCP tool (只读会话查阅).
 *
 * <p>A session transcript is unbounded: a long turn carries whole tool outputs, so the raw
 * message list can easily run into megabytes — far past what an agent can afford to pull into its
 * context in one call, and past what a JSON-RPC line should carry. This class therefore renders a
 * <b>window</b> of the transcript:
 *
 * <ul>
 *   <li>the window is the newest {@code limit} messages (default {@value #DEFAULT_LIMIT},
 *       capped at {@value #MAX_LIMIT}); each message carries its absolute {@code index} in the
 *       full transcript, and the response carries {@code next_before_index} so the caller pages
 *       towards older messages by passing it back as {@code before_index};</li>
 *   <li>each individual field (message content, part text, tool arguments/result) is clipped at
 *       {@value #MAX_FIELD_CHARS} chars with an explicit {@code …[truncated +N chars]} marker —
 *       nothing is dropped silently;</li>
 *   <li>a single message is capped at {@value #MAX_MESSAGE_CHARS} chars (trailing parts are
 *       omitted and counted in {@code omitted_parts}), and the whole response at
 *       {@value #MAX_RESULT_CHARS} — the window always keeps at least one message, so paging
 *       always makes progress.</li>
 * </ul>
 *
 * <p><b>Shape:</b> {@code parts} is the authoritative ordered timeline (text / thinking / tool /
 * steer) of an assistant turn. Rows persisted before the timeline column exist carry an empty
 * {@code parts} and only the flat {@code tool_calls} list — those render {@code tool_calls}
 * instead, so no information is lost; a message with a timeline never repeats its calls in the
 * flat list (the timeline already contains them, in order).
 */
public final class SessionTranscript {

    /** Messages returned when the caller gives no {@code limit}. */
    public static final int DEFAULT_LIMIT = 20;

    /** Hard cap on {@code limit}: bounds one round trip even for a very long transcript. */
    public static final int MAX_LIMIT = 100;

    /** Per-field clip for text / tool argument / tool result strings. */
    public static final int MAX_FIELD_CHARS = 4_000;

    /** Per-message clip; trailing parts beyond it are omitted and counted. */
    public static final int MAX_MESSAGE_CHARS = 40_000;

    /** Per-response clip; the window is trimmed to the newest messages that fit. */
    public static final int MAX_RESULT_CHARS = 80_000;

    private SessionTranscript() {
    }

    /** Clamps a requested window size into {@code [1, MAX_LIMIT]}. */
    public static int clampLimit(int requested) {
        return Math.min(Math.max(requested, 1), MAX_LIMIT);
    }

    /**
     * Renders the window of {@code messages} ending at {@code beforeIndex} (exclusive).
     *
     * @param session     the session row (metadata block)
     * @param projectId   owning project of the session's ticket; nullable (unaffiliated ticket)
     * @param messages    the full transcript, oldest first
     * @param limit       window size (already clamped by {@link #clampLimit})
     * @param beforeIndex exclusive upper bound; {@code null} = newest messages
     * @return the tool result payload
     */
    public static Map<String, Object> render(Session session, String projectId,
                                             List<SessionMessage> messages,
                                             int limit, Integer beforeIndex) {
        int total = messages.size();
        int upper = beforeIndex == null ? total : Math.min(Math.max(beforeIndex, 0), total);
        int windowStart = Math.max(0, upper - limit);

        // Walk the window newest → oldest, keeping what fits the response budget. The newest end is
        // what a caller wants first (what the session is doing now), so the budget trims the OLD
        // side; the window always keeps at least one message, otherwise a single huge turn could
        // make the tool return nothing and paging would stall.
        List<Map<String, Object>> rendered = new ArrayList<>();
        int used = 0;
        int firstKept = -1;
        for (int i = upper - 1; i >= windowStart; i--) {
            Map<String, Object> message = messageJson(messages.get(i), i);
            int size = McpJsonRpc.serialize(message).length();
            if (!rendered.isEmpty() && used + size > MAX_RESULT_CHARS) {
                break;
            }
            rendered.add(message);
            used += size;
            firstKept = i;
        }
        Collections.reverse(rendered);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", sessionJson(session, projectId));
        result.put("total_messages", total);
        result.put("returned", rendered.size());
        result.put("first_index", rendered.isEmpty() ? null : (long) firstKept);
        // Paging anchor: reading again with before_index = next_before_index returns the messages
        // just BEFORE this window; null means the window already starts at the oldest message.
        result.put("next_before_index", rendered.isEmpty() || firstKept <= 0 ? null : (long) firstKept);
        result.put("has_more", !rendered.isEmpty() && firstKept > 0);
        boolean budgetLimited = !rendered.isEmpty() && rendered.size() < upper - windowStart;
        if (budgetLimited) {
            result.put("budget_limited", true);
        }
        result.put("messages", rendered);
        return result;
    }

    /** Session metadata block (id / ticket / project / status / model attribution). */
    public static Map<String, Object> sessionJson(Session s, String projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("ticket_no", s.ticketNo());
        m.put("project_id", projectId);
        m.put("agent_config_id", s.agentConfigId());
        m.put("cli", s.cli().name());
        m.put("status", s.status().name());
        m.put("title", s.title());
        m.put("archived", s.archived());
        m.put("cli_session_id", s.cliSessionId());
        m.put("started_at", s.startedAt().toString());
        m.put("finished_at", s.finishedAt() == null ? null : s.finishedAt().toString());
        if (s.cumulativeUsage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", s.cumulativeUsage().promptTokens());
            usage.put("completion_tokens", s.cumulativeUsage().completionTokens());
            usage.put("total_tokens", s.cumulativeUsage().totalTokens());
            m.put("cumulative_usage", usage);
        }
        m.put("override_provider", s.overrideProvider());
        m.put("override_model", s.overrideModel());
        m.put("override_variant", s.overrideVariant());
        m.put("permission_auto_accept", s.permissionAutoAccept());
        m.put("permission_mode", s.permissionMode());
        return m;
    }

    private static Map<String, Object> messageJson(SessionMessage m, int index) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", (long) index);
        out.put("id", m.id());
        out.put("role", m.role().name());
        out.put("timestamp", m.timestamp().toString());
        boolean[] clipped = {false};
        out.put("content", clip(m.content(), clipped));

        // Timeline is authoritative when present; legacy rows (pre-parts) fall back to the flat
        // tool-call list so their calls stay visible.
        if (!m.parts().isEmpty()) {
            List<Map<String, Object>> parts = new ArrayList<>();
            int omitted = 0;
            int used = 0;
            for (TurnPart p : m.parts()) {
                Map<String, Object> part = partJson(p, clipped);
                int size = McpJsonRpc.serialize(part).length();
                if (!parts.isEmpty() && used + size > MAX_MESSAGE_CHARS) {
                    omitted = m.parts().size() - parts.size();
                    break;
                }
                parts.add(part);
                used += size;
            }
            out.put("parts", parts);
            if (omitted > 0) {
                out.put("omitted_parts", omitted);
            }
        } else if (!m.toolCalls().isEmpty()) {
            out.put("tool_calls", toolCallsJson(m.toolCalls(), clipped));
        }

        if (m.usage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", m.usage().promptTokens());
            usage.put("completion_tokens", m.usage().completionTokens());
            usage.put("total_tokens", m.usage().totalTokens());
            out.put("usage", usage);
        }
        // V22/V23 逐消息模型标注；存量行为 null，读者按会话当前模型近似。
        out.put("model_provider", m.modelProvider());
        out.put("model_id", m.modelId());
        out.put("reasoning_variant", m.reasoningVariant());
        if (m.degraded()) {
            out.put("degraded", true);
        }
        if (clipped[0]) {
            out.put("truncated", true);
        }
        return out;
    }

    private static Map<String, Object> partJson(TurnPart p, boolean[] clipped) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", p.type());
        if (p.isTool()) {
            part.put("name", p.name());
            part.put("arguments_json", clip(p.argumentsJson(), clipped));
            part.put("result_json", clip(p.resultJson(), clipped));
        } else if (p.isSteer()) {
            // steer 段的 name 是被吞并 USER 行的 id（渲染对账锚点），不是工具名。
            part.put("name", p.name());
            part.put("text", clip(p.text(), clipped));
        } else {
            part.put("text", clip(p.text(), clipped));
        }
        return part;
    }

    private static List<Map<String, Object>> toolCallsJson(List<ToolCall> calls, boolean[] clipped) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolCall c : calls) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("arguments_json", clip(c.argumentsJson(), clipped));
            m.put("result_json", clip(c.resultJson(), clipped));
            out.add(m);
        }
        return out;
    }

    /**
     * Clips one field to {@link #MAX_FIELD_CHARS}, appending an explicit marker with the number of
     * dropped chars. The marker keeps truncation visible to the agent: a silently shortened tool
     * result reads as the whole truth and would be acted on as such.
     */
    private static String clip(String raw, boolean[] clipped) {
        if (raw == null || raw.length() <= MAX_FIELD_CHARS) {
            return raw;
        }
        clipped[0] = true;
        return raw.substring(0, MAX_FIELD_CHARS) + "\n…[truncated +" + (raw.length() - MAX_FIELD_CHARS) + " chars]";
    }
}
