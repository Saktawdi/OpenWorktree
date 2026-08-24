package gate.ports.store;

import gate.domain.session.AgentConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** CRUD repository for reusable agent configurations (执行文档-后端-web §5.2, §10 S3). */
public interface AgentConfigRepository {

    List<AgentConfig> findAll();

    Optional<AgentConfig> find(String id);

    void insert(AgentConfig config, Instant now);

    void update(AgentConfig config, Instant now);

    void delete(String id);
}
