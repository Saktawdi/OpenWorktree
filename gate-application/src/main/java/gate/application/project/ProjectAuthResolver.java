package gate.application.project;

import gate.domain.config.GateConfig;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.ports.ProjectRepository;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the authoritative repo a ticket operates on.
 *
 * <p>A ticket bound to a project uses that project's {@code auth_repo} (and {@code target_ref}
 * when set); everything else falls back to the gate's {@code gate.toml} topology. Before this
 * resolver every ticket cloned from the single configured auth repo, so projects sharing one gate
 * instance also shared one {@code main}: one project's published history became every other
 * project's clone base (the T-107 incident, where a gate-project ticket cloned ha-orchestrator
 * code).
 *
 * <p>{@code projects} may be null in legacy wirings; resolution then always falls back to config.
 */
public final class ProjectAuthResolver {

    /** The (auth repo, target ref) pair a ticket's topology hangs off. */
    public record AuthTarget(RepoRef authRepo, String targetRef) {
    }

    private final ProjectRepository projects;
    private final GateConfig config;

    public ProjectAuthResolver(ProjectRepository projects, GateConfig config) {
        this.projects = projects;
        this.config = config;
    }

    /** Creation-time resolution: the project may be null (unaffiliated ticket). */
    public AuthTarget forNewTicket(Project project) {
        RepoRef configAuth = RepoRef.of(config.authRepo());
        if (project != null && project.authRepo() != null && !project.authRepo().isBlank()) {
            String ref = project.targetRef() != null && !project.targetRef().isBlank()
                    ? project.targetRef() : config.primaryTargetRef();
            return new AuthTarget(RepoRef.of(Path.of(project.authRepo())), ref);
        }
        return new AuthTarget(configAuth, config.primaryTargetRef());
    }

    /** Downstream resolution (presubmit/review/publish): keyed by the ticket's own project. */
    public AuthTarget forTicket(Ticket ticket) {
        if (projects != null && ticket.projectId() != null) {
            Project project = projects.find(ticket.projectId()).orElse(null);
            if (project != null && project.authRepo() != null && !project.authRepo().isBlank()) {
                return new AuthTarget(RepoRef.of(Path.of(project.authRepo())), ticket.targetRef());
            }
        }
        return new AuthTarget(RepoRef.of(config.authRepo()), ticket.targetRef());
    }

    /** Resolves the registered workspace for a project-bound ticket, if legacy wiring has projects. */
    public Optional<Path> workspaceFor(Ticket ticket) {
        if (projects == null || ticket.projectId() == null) {
            return Optional.empty();
        }
        return projects.find(ticket.projectId())
                .map(Project::workspacePath)
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of);
    }

    /** Default auth repo path for a newly registered project: a sibling of the configured repo. */
    public Path defaultProjectAuthRepo(String projectId) {
        String token = projectId.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        if (token.isBlank()) {
            token = "project";
        }
        return config.authRepo().getParent().resolve("auth-" + token + ".git");
    }
}
