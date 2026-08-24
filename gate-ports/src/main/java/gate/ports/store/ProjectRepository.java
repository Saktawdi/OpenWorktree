package gate.ports.store;

import gate.domain.project.Project;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the {@code project} registry (V5 web console — codex-style workspace adoption).
 *
 * <p>Each project owns one isolated ticket board and drives the home project board. The gate
 * topology itself still lives in {@code gate.toml} (ADR-14 single-project topology).
 */
public interface ProjectRepository {

    void insert(Project project);

    Optional<Project> find(String id);

    /** Looks up a project by its normalized workspace path (duplicate guard on create). */
    Optional<String> findIdByWorkspacePath(String workspacePath);

    List<Project> findAll();

    void update(Project project);

    /** Deletes the row; callers detach tickets first via {@link TicketRepository#clearProject}. */
    void deleteById(String id);
}
