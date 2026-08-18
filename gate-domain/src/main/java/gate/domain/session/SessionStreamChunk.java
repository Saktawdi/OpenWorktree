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
        SessionStreamChunk.DoneChunk {

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
}
