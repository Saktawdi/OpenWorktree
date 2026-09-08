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
 * @param parts     chronological turn segments (text/thinking/tool) for assistant turns;
 *                  empty when the row predates parts or carries no timeline (USER/ERROR)
 * @param modelProvider provider of the model that actually produced this reply (V22);
 *                  null on legacy rows / USER / ERROR — readers fall back to the
 *                  session's current model approximation
 * @param modelId   bare model id that actually produced this reply (V22); null likewise
 * @param reasoningVariant reasoning-effort pinned for this turn (V23; upstream does not
 *                  echo it, so this is the request-side value: opencode prompt body /
 *                  claude --effort); null on legacy rows / unset effort
 */
public record SessionMessage(
        String id,
        String sessionId,
        Role role,
        String content,
        List<ToolCall> toolCalls,
        SessionUsage usage,
        boolean degraded,
        Instant timestamp,
        List<TurnPart> parts,
        String modelProvider,
        String modelId,
        String reasoningVariant) {

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
        parts = parts == null ? List.of() : List.copyOf(parts);
        if (modelProvider != null && modelProvider.isBlank()) {
            modelProvider = null;
        }
        if (modelId != null && modelId.isBlank()) {
            modelId = null;
        }
        if (reasoningVariant != null && reasoningVariant.isBlank()) {
            reasoningVariant = null;
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
    }

    /** Legacy shape (pre-parts rows and callers that do not build a timeline). */
    public SessionMessage(String id, String sessionId, Role role, String content,
                          List<ToolCall> toolCalls, SessionUsage usage, boolean degraded,
                          Instant timestamp) {
        this(id, sessionId, role, content, toolCalls, usage, degraded, timestamp, List.of());
    }

    /** Legacy shape (pre-V22 rows): no per-message model/variant attribution. */
    public SessionMessage(String id, String sessionId, Role role, String content,
                          List<ToolCall> toolCalls, SessionUsage usage, boolean degraded,
                          Instant timestamp, List<TurnPart> parts) {
        this(id, sessionId, role, content, toolCalls, usage, degraded, timestamp, parts, null, null, null);
    }
}
