package gate.ports;

import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Send a message; returns a task id (async) whose progress streams via {@link #streamEvents}. */
    String sendMessage(SendRequest request);

    /** Abort an in-flight message or tear down the session process. */
    void abort(String sessionId);

    /** Read-only history, independent of any running process. */
    List<SessionMessage> getHistory(String sessionId);

    /** SSE event stream: replays past messages, then streams live. */
    Stream<SessionEvent> streamEvents(String sessionId);

    record StartRequest(
            String ticketNo,
            String agentConfigId,
            String clonePath,
            String targetRef,
            String initialPrompt,
            Map<String, String> env) {
    }

    record SendRequest(
            String sessionId,
            String message,
            boolean resume) {
    }

    record SessionEvent(
            String sessionId,
            SessionMessage message,
            String kind) {
    }
}
