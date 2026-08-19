package gate.web.project;

import gate.ports.ProjectRepository;

/**
 * Project capability handler (EX-001).
 * Owns /api/projects, /api/workspaces.
 */
public final class ProjectRoutes {
    private final ProjectRepository projects;

    public ProjectRoutes(ProjectRepository projects) {
        this.projects = projects;
    }
}
