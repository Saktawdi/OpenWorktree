package gate.domain.session;

import java.time.Instant;
import java.util.List;

/**
 * One message in a session — 执行文档-后端-web §5.2.
 *
 * @param id        UUID
 * @param sessionId owning session
 * @param role      USER | ASSISTANT | TOOL | ERROR
 * @param content   message content (already read from blob by repository)
 * @param toolCalls tool call list; empty for non-tool messages
 * @param usage     per-message usage; null for non-LLM messages
 * @param degraded  usage parse failure marker
 * @param timestamp creation time
 */
public record SessionMessage(
        String id,
        String sessionId,
        Role role,
        String content,
        List<ToolCall> toolCalls,
        SessionUsage usage,
        boolean degraded,
        Instant timestamp) {

    public SessionMessage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (content == null) {
            content = "";
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
    }
}
