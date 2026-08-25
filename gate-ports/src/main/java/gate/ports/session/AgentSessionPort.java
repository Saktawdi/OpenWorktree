package gate.ports.session;
import gate.ports.engine.ReviewEngine;


import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.PermissionRequest;
import gate.domain.session.QuestionRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Agent session orchestration port (执行文档-后端-web §5.1).
 *
 * <p>Two adapters share this contract (ADR-12): {@code OpenCodeServeAdapter} and
 * {@code ClaudeHeadlessAdapter}, dispatched by {@link gate.domain.session.AgentConfig#cli()}.
 *
 * <p>Contract mirroring {@code ReviewEngine}: the port returns structured records and never throws
 * on the happy path; transport/parse failures surface as a {@link SessionMessage} with
 * {@code role=ERROR} and a degraded marker, never as an exception that escapes the port.
 */
public interface AgentSessionPort {

    /** Start a session bound to a ticket's clone; returns the new session. */
    Session start(StartRequest request);

    /** Send a message; returns a task id (async) whose progress streams via {@link #attachListener}. */
    String sendMessage(SendRequest request);

    /** Abort an in-flight message or tear down the session process. */
    void abort(String sessionId);

    /** Read-only history, independent of any running process. */
    List<SessionMessage> getHistory(String sessionId);

    /** Legacy / batch event stream: replays past messages. */
    Stream<SessionEvent> streamEvents(String sessionId);

    /**
     * Attach a real-time event listener for live streaming chunks.
     * Returns an {@link AutoCloseable} to unregister the listener.
     */
    AutoCloseable attachListener(String sessionId, Consumer<SessionStreamChunk> listener);

    /**
     * Reply to a pending opencode permission request for the given session.
     * {@code response} is one of {@code once|always|reject}; only meaningful for opencode
     * sessions (the web layer rejects other CLIs before reaching here).
     */
    void respondPermission(String sessionId, String permissionId, String response);

    /** Live snapshot of this session's unresolved pending permission requests. */
    List<PermissionRequest> pendingPermissions(String sessionId);

    /**
     * Live snapshot of this session's unresolved pending question requests (question 工具).
     * Default: none — only the opencode adapter produces asks.
     */
    default List<QuestionRequest> pendingQuestions(String sessionId) {
        return List.of();
    }

    /**
     * Answer a pending question request: one selected-label list per question, in order.
     * Only meaningful for opencode sessions.
     */
    default void respondQuestion(String sessionId, String requestId, List<List<String>> answers) {
        throw new UnsupportedOperationException(
                "questions are only supported for opencode sessions");
    }

    /** Reject (dismiss) a pending question request; the agent sees the ask as aborted. */
    default void rejectQuestion(String sessionId, String requestId) {
        throw new UnsupportedOperationException(
                "questions are only supported for opencode sessions");
    }

    /**
     * 确保会话的 CLI 端点可用并返回端口；必要时按会话记录重新拉起 CLI 进程并重接事件流
     * （opencode 懒复活：后端重启后旧会话首次使用时重建 serve）。仅 opencode 适配器实现，
     * 其余实现抛 {@link UnsupportedOperationException}。
     */
    default int ensureEndpoint(String sessionId) {
        throw new UnsupportedOperationException(
                "endpoint resurrection is only supported for opencode sessions");
    }

    /**
     * 有进行中回合的 session id 快照（运行中 = turn in-flight）。
     * 默认返回空集合，保持既有实现编译通过；具体适配器覆盖为排序后的不可变快照。
     */
    default Set<String> busySessionIds() {
        return Collections.emptySet();
    }

    record StartRequest(
            String ticketNo,
            String agentConfigId,
            String clonePath,
            String targetRef,
            String initialPrompt,
            Map<String, String> env) {
    }

    /**
     * One image attachment carried alongside a send. {@code dataBase64} is the raw base64
     * payload (no data-URL prefix); adapters turn it into a data URL file part.
     */
    record Attachment(String filename, String mime, String dataBase64) {
    }

    record SendRequest(
            String sessionId,
            String message,
            boolean resume,
            List<Attachment> attachments) {

        public SendRequest {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** Legacy shape: no attachments (still the common case). */
        public SendRequest(String sessionId, String message, boolean resume) {
            this(sessionId, message, resume, List.of());
        }
    }

    record SessionEvent(
            String sessionId,
            SessionMessage message,
            String kind) {
    }
}

