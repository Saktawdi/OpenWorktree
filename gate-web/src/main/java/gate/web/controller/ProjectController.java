package gate.web.controller;

import gate.adapters.git.GitCli;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
import gate.ports.git.TopologyInitializer;
import gate.ports.git.WorkspaceSyncer;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Project and Workspace Management Controller.
 * Owns /api/workspaces and /api/projects routes.
 */
public final class ProjectController implements WebController {

    private final ProjectRepository projects;
    private final TicketRepository tickets;
    private final TopologyInitializer topologyInitializer;
    private final GateConfig config;
    private final WorkspaceSyncer workspaceSyncer;
    private final gate.ports.git.CloneBaseSyncer cloneBaseSyncer;
    private final GitCli git;
    private final gate.ports.infra.Clock clock;

    public ProjectController(ProjectRepository projects, TicketRepository tickets,
                             TopologyInitializer topologyInitializer, GateConfig config,
                             WorkspaceSyncer workspaceSyncer, gate.ports.git.CloneBaseSyncer cloneBaseSyncer,
                             GitCli git, gate.ports.infra.Clock clock) {
        this.projects = projects;
        this.tickets = tickets;
        this.topologyInitializer = topologyInitializer;
        this.config = config;
        this.workspaceSyncer = workspaceSyncer;
        this.cloneBaseSyncer = cloneBaseSyncer;
        this.git = git;
        this.clock = clock;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/workspaces", this::listWorkspaces);
        app.post("/api/workspaces", this::inspectWorkspace);
        app.post("/api/workspaces/mkdir", this::createWorkspaceDir);
        app.get("/api/projects", this::listProjects);
        app.post("/api/projects", this::createProject);
        app.post("/api/projects/reorder", this::reorderProjects);
        app.put("/api/projects/{id}", this::updateProject);
        app.delete("/api/projects/{id}", this::deleteProject);
        app.post("/api/projects/{id}/workspace-sync", this::syncWorkspace);
    }

    public void listWorkspaces(Context ctx) {
        ctx.status(HttpStatus.OK);
        ctx.json(renderWorkspaces(null));
    }

    public void inspectWorkspace(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String path = req.get("path") == null ? null : req.get("path").toString();
        ctx.status(HttpStatus.OK);
        ctx.json(renderWorkspaces(path));
    }

    /**
     * 在已存在的父目录下新建一层子目录（目录选择器的「新建文件夹」）。只创建一层、
     * 父目录必须真实存在——不做静默的递归创建，路径打错时立刻报错而不是造出一串
     * 错层级的空目录。
     */
    public void createWorkspaceDir(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String parentRaw = required(req, "parent");
        String name = required(req, "name");
        if (!name.matches("[A-Za-z0-9._\\- ()\\u4e00-\\u9fa5]+") || name.startsWith(".")) {
            throw new GateException(GateErrorCode.USAGE,
                    "invalid directory name: " + name);
        }
        Path parent = normalizeWorkspace(parentRaw);
        if (!Files.isDirectory(parent)) {
            throw new GateException(GateErrorCode.USAGE, "parent directory does not exist: " + parent);
        }
        Path target = parent.resolve(name).normalize();
        if (!target.startsWith(parent) || target.getParent() == null
                || !target.getParent().equals(parent)) {
            throw new GateException(GateErrorCode.USAGE, "invalid directory name: " + name);
        }
        try {
            Files.createDirectory(target);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot create directory " + target + ": " + e.getMessage(), e);
        }
        ctx.status(HttpStatus.CREATED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", target.toString());
        ctx.json(body);
    }

    public void listProjects(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project p : projects.findAll()) {
            rows.add(projectJson(p));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projects", rows);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void createProject(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String name = required(req, "name");
        String workspaceRaw = required(req, "workspace_path");
        Path workspace = normalizeWorkspace(workspaceRaw);
        if (!workspace.isAbsolute()) {
            throw new GateException(GateErrorCode.USAGE, "workspace_path must be absolute: " + workspaceRaw);
        }
        if (projects.findIdByWorkspacePath(workspace.toString()).isPresent()) {
            throw new GateException(GateErrorCode.USAGE,
                    "workspace already registered as a project: " + workspace);
        }
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot create workspace " + workspace + ": " + e.getMessage(), e);
        }
        boolean initGit = Boolean.parseBoolean(String.valueOf(req.getOrDefault("init_git", "false")));
        String requestedRef = normalizeTargetRef(req);
        String initBranch = "main";
        if (requestedRef != null && requestedRef.startsWith("refs/heads/")) {
            initBranch = requestedRef.substring("refs/heads/".length());
        }
        if (initGit && !Files.exists(workspace.resolve(".git"))) {
            gate.ports.infra.ProcessRunner.ProcRun r = git.run(workspace, Map.of(), "init", "-b", initBranch);
            if (!r.ok()) {
                r = git.run(workspace, Map.of(), "init");
            }
            if (!r.ok()) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "git init failed in " + workspace + ": " + r.stderrFirstLine());
            }
            // Old git without `init -b`: point the unborn HEAD at the requested branch explicitly.
            if (!initBranch.equals("main")) {
                git.run(workspace, Map.of(), "symbolic-ref", "HEAD", "refs/heads/" + initBranch);
            }
        }
        String priority = TicketController.parsePriority(req);
        String size = parseProjectSize(req);
        List<String> tags = parseProjectTags(req);
        Instant now = clock.now();
        String id = uniqueProjectId(name);
        Path projectAuthRepo = new gate.application.project.ProjectAuthResolver(projects, config)
                .defaultProjectAuthRepo(id);
        // Resolution order: explicit request > the workspace's current branch (most repos are
        // master in the wild) > the gate-level primary ref. Never a silent hardcode.
        String effectiveTargetRef = requestedRef != null
                ? requestedRef
                : java.util.Optional.ofNullable(detectWorkspaceBranch(workspace))
                        .orElse(config.primaryTargetRef());
        topologyInitializer.initAuthRepo(RepoRef.of(projectAuthRepo), effectiveTargetRef,
                config.approvalsDir());
        // T-125: when the workspace already carries real history, the mirror adopts it as the
        // base instead of keeping the empty gate seed — otherwise the first clones are cut from
        // an empty baseline and the project's actual files never reach them. Fail-open: a
        // non-git or commit-less workspace just keeps the seed.
        if (cloneBaseSyncer != null && Files.exists(workspace.resolve(".git"))) {
            cloneBaseSyncer.importWorkspaceBase(RepoRef.of(workspace), RepoRef.of(projectAuthRepo),
                    effectiveTargetRef);
        }
        Project p = new Project(id, name, workspace.toString(),
                effectiveTargetRef, projectAuthRepo.toString(), priority, size, tags,
                false, nextSortOrder(), now, now);
        projects.insert(p);
        ctx.status(HttpStatus.CREATED);
        ctx.json(projectJson(p));
    }

    public void updateProject(Context ctx) {
        String id = ctx.pathParam("id");
        Project existing = projects.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + id));
        Map<String, Object> req = Json.parseObject(ctx.body());
        if (!req.containsKey("name") && !req.containsKey("workspace_path")
                && !req.containsKey("target_ref") && !req.containsKey("priority")
                && !req.containsKey("size") && !req.containsKey("tags")
                && !req.containsKey("starred") && !req.containsKey("sort_order")) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide name, workspace_path, target_ref, priority, size, tags, starred or sort_order");
        }
        String name = req.containsKey("name") ? required(req, "name") : existing.name();
        String workspace = existing.workspacePath();
        if (req.containsKey("workspace_path")) {
            workspace = normalizeWorkspace(required(req, "workspace_path")).toString();
            var other = projects.findIdByWorkspacePath(workspace);
            if (other.isPresent() && !other.get().equals(id)) {
                throw new GateException(GateErrorCode.USAGE,
                        "workspace already registered as a project: " + workspace);
            }
        }
        String targetRef = req.containsKey("target_branch") || req.containsKey("target_ref")
                ? java.util.Optional.ofNullable(normalizeTargetRef(req))
                        .orElseGet(() -> config.primaryTargetRef())
                : effectiveTargetRef(existing.targetRef());
        // Base switched: make the project's auth repo coherent with it — seed the branch if
        // missing, point HEAD at it, reinstall the per-repo hook with the updated whitelist
        // (initAuthRepo is idempotent; existing ticket branches are untouched).
        if (!targetRef.equals(existing.targetRef())
                && existing.authRepo() != null && !existing.authRepo().isBlank()
                && Files.exists(Path.of(existing.authRepo()))) {
            topologyInitializer.initAuthRepo(RepoRef.of(Path.of(existing.authRepo())), targetRef,
                    config.approvalsDir());
        }
        String priority = req.containsKey("priority") ? TicketController.parsePriority(req) : existing.priority();
        String size = req.containsKey("size") ? parseProjectSize(req) : existing.size();
        List<String> tags = req.containsKey("tags") ? parseProjectTags(req) : existing.tags();
        boolean starred = req.containsKey("starred") ? parseBoolean(req.get("starred")) : existing.starred();
        // 星标/排序是纯整理动作：只改键携带时也不该刷新「更新于」，避免掩盖真实的内容变更时间
        long sortOrder = req.containsKey("sort_order") ? parseSortOrder(req.get("sort_order")) : existing.sortOrder();
        Project updated = new Project(id, name, workspace, targetRef, existing.authRepo(),
                priority, size, tags, starred, sortOrder, existing.createdAt(), clock.now());
        projects.update(updated);
        ctx.status(HttpStatus.OK);
        ctx.json(projectJson(updated));
    }

    /**
     * Console drag-order persistence: {@code {"order": ["idA","idB",…]}} assigns dense
     * {@code sort_order} 1..N in the given sequence; ids not in the list are left untouched.
     */
    public void reorderProjects(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        Object raw = req.get("order");
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "order must be an array of project ids");
        }
        List<Project> all = projects.findAll();
        List<Project> updated = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item == null) {
                throw new GateException(GateErrorCode.USAGE, "order entry must not be null");
            }
            String id = item.toString();
            Project p = all.stream().filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new GateException(GateErrorCode.USAGE, "no such project: " + id));
            updated.add(new Project(p.id(), p.name(), p.workspacePath(), p.targetRef(), p.authRepo(),
                    p.priority(), p.size(), p.tags(), p.starred(), i + 1L, p.createdAt(), clock.now()));
        }
        updated.forEach(projects::update);
        ctx.status(HttpStatus.OK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.json(body);
    }

    public void deleteProject(Context ctx) {
        String id = ctx.pathParam("id");
        if (projects.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such project: " + id);
        }
        tickets.clearProject(id, clock.now());
        projects.deleteById(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void syncWorkspace(Context ctx) {
        String projectId = ctx.pathParam("id");
        Project p = projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
        if (workspaceSyncer == null || git == null) {
            throw new GateException(GateErrorCode.USAGE, "workspace sync is not configured on this gate");
        }
        if (p.authRepo() == null || p.authRepo().isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "project has no auth repo: " + projectId);
        }
        Path ws = normalizeWorkspace(p.workspacePath());
        if (!Files.isDirectory(ws) || !Files.exists(ws.resolve(".git"))) {
            throw new GateException(GateErrorCode.USAGE,
                    "workspace is not a git repository: " + ws);
        }
        String targetRef = p.targetRef() == null || p.targetRef().isBlank()
                ? config.primaryTargetRef() : p.targetRef();
        RepoRef auth = RepoRef.of(Path.of(p.authRepo()));
        ProcessRunner.ProcRun tipRun = git.run(auth, "rev-parse", "--verify", targetRef);
        if (!tipRun.ok()) {
            throw new GateException(GateErrorCode.USAGE,
                    "auth repo has no such ref: " + targetRef + " (" + auth.pathString() + ")");
        }
        ObjectId authTip = ObjectId.of(tipRun.stdout().trim());

        String branch = targetRef.startsWith("refs/heads/")
                ? targetRef.substring("refs/heads/".length()) : targetRef;
        String before = branchTip(ws, "refs/heads/" + branch);
        WorkspaceSyncer.SyncOutcome outcome = workspaceSyncer.syncWorkspace(
                RepoRef.of(ws), auth, targetRef, authTip);
        String after = branchTip(ws, "refs/heads/" + branch);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.put("status", outcome.status().name());
        body.put("note", outcome.note());
        body.put("target_ref", targetRef);
        body.put("auth_tip", authTip.hex());
        body.put("workspace_tip_before", before);
        body.put("workspace_tip_after", after);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private String branchTip(Path repo, String ref) {
        ProcessRunner.ProcRun r = git.run(RepoRef.of(repo), "rev-parse", "--verify", "-q", ref);
        return r.ok() ? r.stdout().trim() : null;
    }

    private Map<String, Object> renderWorkspaces(String rawPath) {
        Path dir = normalizeWorkspace(rawPath == null || rawPath.isBlank()
                ? System.getProperty("user.home") : rawPath);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", dir.toString());
        body.put("parent", dir.getParent() == null ? null : dir.getParent().toString());
        body.put("exists", Files.isDirectory(dir));
        // 前端据此适配路径提示与默认值：Linux 容器里可直接填不存在路径（注册时自动创建），
        // Windows 的盘符相对路径会被 toAbsolutePath 吸到当前盘，必须提示选绝对路径。
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        body.put("platform", os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux");
        body.put("user_home", System.getProperty("user.home"));
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
        return body;
    }

    private Map<String, Object> projectJson(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("workspace_path", p.workspacePath());
        m.put("target_ref", p.targetRef());
        m.put("auth_repo", p.authRepo());
        m.put("priority", p.priority());
        m.put("size", p.size());
        m.put("tags", p.tags());
        m.put("starred", p.starred());
        m.put("sort_order", p.sortOrder());
        List<Ticket> projectTickets = tickets.findAllByProject(p.id());
        m.put("ticket_count", projectTickets.size());
        m.put("active_ticket_count", (int) projectTickets.stream()
                .filter(t -> t.stage() != null && !t.stage().isTerminal()).count());
        m.put("created_at", p.createdAt().toString());
        m.put("updated_at", p.updatedAt().toString());
        return m;
    }

    private String effectiveTargetRef(String targetRef) {
        return targetRef == null || targetRef.isBlank() ? config.primaryTargetRef() : targetRef;
    }

    /** 新项目落在手动排序尾部：1-based 最大值+1；全部未排过时从 1 开始。 */
    private long nextSortOrder() {
        return projects.findAll().stream().mapToLong(Project::sortOrder).max().orElse(0L) + 1L;
    }

    private static boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (!s.equals("true") && !s.equals("false")) {
            throw new GateException(GateErrorCode.USAGE, "starred must be a boolean: " + raw);
        }
        return Boolean.parseBoolean(s);
    }

    private static long parseSortOrder(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new GateException(GateErrorCode.USAGE, "sort_order must be an integer: " + raw);
        }
    }

    /**
     * Normalizes the request's {@code target_branch} (preferred) or {@code target_ref} into a
     * full {@code refs/heads/<segment>} ref — "master" and "refs/heads/master" are both accepted
     * — or returns {@code null} when the request carries neither. Mirrors the ticket-side
     * {@code resolveTicketTargetRef} validation: a single path segment, no trailing ".lock".
     */
    private String normalizeTargetRef(Map<String, Object> req) {
        String raw = req.containsKey("target_branch") ? str(req, "target_branch") : str(req, "target_ref");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim();
        if (name.startsWith("refs/heads/")) {
            name = name.substring("refs/heads/".length());
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".lock")
                || !name.matches("[A-Za-z0-9._-]+") || name.length() > 80) {
            throw new GateException(GateErrorCode.USAGE,
                    "target_branch must match [A-Za-z0-9._-]+ (single segment, no slash): " + raw);
        }
        return "refs/heads/" + name;
    }

    /** The workspace repo's current branch, or {@code null} when it is not a git repo / detached. */
    private String detectWorkspaceBranch(Path workspace) {
        if (!Files.exists(workspace.resolve(".git"))) {
            return null;
        }
        ProcessRunner.ProcRun r = git.run(RepoRef.of(workspace), "rev-parse", "--abbrev-ref", "HEAD");
        if (!r.ok()) {
            return null;
        }
        String branch = r.stdout().trim();
        if (branch.isEmpty() || "HEAD".equals(branch)) {
            return null;
        }
        return "refs/heads/" + branch;
    }

    private static String parseProjectSize(Map<String, Object> req) {
        Object raw = req.get("size");
        if (raw == null) {
            return null;
        }
        String size = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (!Project.SIZES.contains(size)) {
            throw new GateException(GateErrorCode.USAGE,
                    "size must be one of " + Project.SIZES + " or null");
        }
        return size;
    }

    private static List<String> parseProjectTags(Map<String, Object> req) {
        Object raw = req.get("tags");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "tags must be an array of strings");
        }
        List<String> tags = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                throw new GateException(GateErrorCode.USAGE, "tag must not be null");
            }
            String tag = item.toString().trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        if (tags.size() > Project.MAX_TAGS) {
            throw new GateException(GateErrorCode.USAGE, "at most " + Project.MAX_TAGS + " tags");
        }
        return tags;
    }

    private String uniqueProjectId(String name) {
        String base = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "project";
        }
        String candidate = base;
        int seq = 1;
        while (projects.find(candidate).isPresent()) {
            candidate = base + "-" + (++seq);
        }
        return candidate;
    }

    private static Path normalizeWorkspace(String raw) {
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }

    private static String required(Map<String, Object> req, String key) {
        String value = str(req, key);
        if (value == null || value.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, key + " is required");
        }
        return value.trim();
    }
}
