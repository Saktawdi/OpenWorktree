package gate.ports.store;

import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for agent sessions and their messages (执行文档-后端-web §6.1, §10 S3/S4).
 *
 * <p>Message content is stored through the blob store by the adapter; {@link SessionMessage#content()}
 * is always the already-read string.
 */
public interface SessionRepository {

    Optional<Session> find(String id);

    List<Session> findByTicket(String ticketNo);

    List<Session> findByAgentConfig(String agentConfigId);

    void insert(Session session);

    void update(Session session);

    /** Sessions in one status (runtime status endpoint, V5 web console). */
    default List<Session> findByStatus(SessionStatus status) {
        throw new UnsupportedOperationException("findByStatus is not supported");
    }

    void insertMessage(SessionMessage message);

    List<SessionMessage> findMessages(String sessionId);

    /** Deletes the session row (workbench session-list removal). Messages must go first — FK. */
    void delete(String id);

    /** Deletes all message rows of a session (run before {@link #delete(String)}). */
    void deleteMessages(String sessionId);
}
