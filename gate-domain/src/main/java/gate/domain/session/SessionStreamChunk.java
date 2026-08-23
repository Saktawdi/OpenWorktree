package gate.domain.session;

import java.time.Instant;

/**
 * Fine-grained streaming chunk emitted during an agent interaction round.
 */
public sealed interface SessionStreamChunk permits
        SessionStreamChunk.ThinkingChunk,
        SessionStreamChunk.ContentChunk,
        SessionStreamChunk.ToolCallChunk,
        SessionStreamChunk.UsageChunk,
        SessionStreamChunk.ErrorChunk,
        SessionStreamChunk.DoneChunk,
        SessionStreamChunk.PermissionAskedChunk,
        SessionStreamChunk.PermissionRepliedChunk,
        SessionStreamChunk.TitleChunk {

    String sessionId();
    Instant timestamp();

    /** Reasoning / thinking token delta (Claude extended thinking / DeepSeek reasoner) */
    record ThinkingChunk(String sessionId, String thinkingDelta, Instant timestamp) implements SessionStreamChunk {}

    /** Regular response content text delta */
    record ContentChunk(String sessionId, String textDelta, Instant timestamp) implements SessionStreamChunk {}

    /** Tool execution lifecycle chunk */
    record ToolCallChunk(
            String sessionId,
            String callId,
            String toolName,
            String argumentDelta,
            String result,
            String status, // STARTING, RUNNING, SUCCESS, FAILED
            Instant timestamp) implements SessionStreamChunk {}

    /** Token usage information */
    record UsageChunk(String sessionId, SessionUsage usage, Instant timestamp) implements SessionStreamChunk {}

    /** Error / interruption event */
    record ErrorChunk(String sessionId, String errorCode, String errorMessage, Instant timestamp) implements SessionStreamChunk {}

    /** Turn completion signal */
    record DoneChunk(String sessionId, String fullMessageId, Instant timestamp) implements SessionStreamChunk {}

    /** An opencode permission.asked request surfaced for the user to approve/reject. */
    record PermissionAskedChunk(String sessionId, PermissionRequest request, Instant timestamp) implements SessionStreamChunk {}

    /** A permission reply (user click or server auto-allow) applied to opencode. */
    record PermissionRepliedChunk(String sessionId, String permissionId, String response, boolean auto, Instant timestamp) implements SessionStreamChunk {}

    /** opencode 自动生成的真实标题（session.updated）落库后立刻广播出去，前端可实时替换占位标签。 */
    record TitleChunk(String sessionId, String title, Instant timestamp) implements SessionStreamChunk {}
}
