package gate.web.project;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.ports.ProjectRepository;
import gate.ports.TopologyInitializer;
import gate.web.ApiRoutes;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project capability handler (EX-001).
 * Owns /api/projects, /api/workspaces.
 * L2 behavior-preserving extraction — validates via same ports, no new owner.
 */
public final class ProjectRoutes {
    private final ProjectRepository projects;
    private final gate.ports.TicketRepository tickets;
    private final TopologyInitializer topologyInitializer;
    private final GateConfig config;

    public ProjectRoutes(ProjectRepository projects, TopologyInitializer topologyInitializer, GateConfig config) {
        this(projects, null, topologyInitializer, config);
    }

    public ProjectRoutes(ProjectRepository projects, gate.ports.TicketRepository tickets, TopologyInitializer topologyInitializer, GateConfig config) {
        this.projects = projects;
        this.tickets = tickets;
        this.topologyInitializer = topologyInitializer;
        this.config = config;
    }

    public ApiRoutes.Response projectList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project p : projects.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("workspace_path", p.workspacePath());
            m.put("target_ref", p.targetRef());
            m.put("auth_repo", p.authRepo());
            m.put("priority", p.priority());
            m.put("size", p.size());
            m.put("tags", p.tags());
            if (tickets != null) {
                try {
                    var list = tickets.findAllByProject(p.id());
                    m.put("ticket_count", list.size());
                    long active = list.stream().filter(t -> t.stage() != null && !t.stage().isTerminal()).count();
                    m.put("active_ticket_count", (int) active);
                } catch (Exception e) {
                    m.put("ticket_count", 0);
                    m.put("active_ticket_count", 0);
                }
            } else {
                m.put("ticket_count", 0);
                m.put("active_ticket_count", 0);
            }
            m.put("created_at", p.createdAt().toString());
            m.put("updated_at", p.updatedAt().toString());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projects", rows);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response workspaces(String rawPath) {
        Path dir = normalizeWorkspace(rawPath == null || rawPath.isBlank()
                ? System.getProperty("user.home") : rawPath);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", dir.toString());
        body.put("parent", dir.getParent() == null ? null : dir.getParent().toString());
        body.put("exists", Files.isDirectory(dir));
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (!Files.isDirectory(root)) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", root.toString());
            r.put("path", root.toString());
            roots.add(r);
        }
        body.put("roots", roots);
        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .limit(500)
                        .forEach(p -> {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("name", p.getFileName().toString());
                            e.put("path", p.toString());
                            e.put("is_git_repo", Files.exists(p.resolve(".git")));
                            e.put("is_registered_project", projects.findIdByWorkspacePath(p.toString()).isPresent());
                            entries.add(e);
                        });
            } catch (IOException e) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot list " + dir + ": " + e.getMessage(), e);
            }
        }
        body.put("directories", entries);
        return new ApiRoutes.Response(200, body);
    }

    private static Path normalizeWorkspace(String raw) {
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }
}
