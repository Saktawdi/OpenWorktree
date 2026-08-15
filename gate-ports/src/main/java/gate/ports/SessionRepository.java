package gate.ports;

import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
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

    void insertMessage(SessionMessage message);

    List<SessionMessage> findMessages(String sessionId);
}
