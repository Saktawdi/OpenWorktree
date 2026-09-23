package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.ports.store.ProjectRepository;
import gate.web.service.RepoViewReader;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目 → 仓库视图 (web console): read-only rendering data for a project's workspace repository — the
 * branch/commit graph, the file tree with per-entry last-commit attribution, and single-commit
 * detail.
 *
 * <p>Reads the workspace repo the project was registered from (never the auth repo: the console
 * mirrors what the human sees in their working copy). Everything is plain {@code git} plumbing via
 * {@link RepoViewReader} (ADR-1: no JGit), read-only, and bounded so a huge repository cannot stall
 * the console.
 *
 * <p>工单侧的同一套视图（工单克隆目录）见 {@link TicketRepoController}——git 读取与泳道算法共用
 * {@link RepoViewReader}，这里只负责「项目 → 仓库路径」这一层解析。
 */
public final class RepoViewController implements WebController {

    private final ProjectRepository projects;
    private final RepoViewReader reader;

    public RepoViewController(ProjectRepository projects, RepoViewReader reader) {
        this.projects = projects;
        this.reader = reader;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/projects/{id}/repo", this::repoView);
        app.get("/api/projects/{id}/commit/<sha>", this::commitDetail);
        app.get("/api/projects/{id}/tree", ctx -> treeView(ctx, List.of()));
        app.get("/api/projects/{id}/tree/<path>", ctx -> {
            String pathParam = ctx.pathParam("path");
            treeView(ctx, List.of(pathParam.split("/")));
        });
    }

    /** GET /api/projects/{id}/repo — branches + topo-ordered commits with lane numbers. */
    public void repoView(Context ctx) {
        Project p = requireProject(ctx.pathParam("id"));
        Path ws = Path.of(p.workspacePath());
        RepoViewReader.requireGitWorkspace(ws);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.put("repo_path", ws.toString());

        RepoViewReader.RepoGraph graph = reader.readGraph(ws, p.targetRef());
        body.put("head", graph.head());
        body.put("auth", authInfo(p));

        List<Map<String, Object>> branchRows = new java.util.ArrayList<>();
        for (RepoViewReader.Branch b : graph.branches()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name());
            m.put("tip", b.tip());
            m.put("lane", b.lane());
            branchRows.add(m);
        }
        List<Map<String, Object>> commitRows = new java.util.ArrayList<>();
        for (RepoViewReader.Commit c : graph.commits()) {
            commitRows.add(commitRow(c));
        }
        body.put("branches", branchRows);
        body.put("commits", commitRows);
        body.put("truncated", graph.truncated());

        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** GET /api/projects/{id}/commit/<sha> — full message + metadata + refs containing it. */
    public void commitDetail(Context ctx) {
        Project p = requireProject(ctx.pathParam("id"));
        Path ws = Path.of(p.workspacePath());
        RepoViewReader.CommitDetail d = reader.readCommit(ws, ctx.pathParam("sha"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.putAll(commitDetailBody(d));

        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** GET /api/projects/{id}/tree[/{path…}] — direct children of one directory in HEAD. */
    public void treeView(Context ctx, List<String> pathSegments) {
        Project p = requireProject(ctx.pathParam("id"));
        Path ws = Path.of(p.workspacePath());
        RepoViewReader.requireGitWorkspace(ws);
        String dir = RepoViewReader.normalizeDir(pathSegments);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.put("path", dir);

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (RepoViewReader.TreeEntry e : reader.readTree(ws, dir)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", e.path());
            m.put("type", e.type());
            m.put("size", e.type().equals("file") ? e.size() : null);
            m.put("last_commit_short", e.lastCommitShort());
            m.put("last_message", e.lastMessage());
            rows.add(m);
        }
        body.put("entries", rows);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /* ─── shared rendering shape (project + ticket reuse these) ─── */

    static Map<String, Object> commitRow(RepoViewReader.Commit c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sha", c.sha());
        m.put("parents", c.parents());
        m.put("message", c.message());
        m.put("author", c.author());
        m.put("time", c.time());
        m.put("refs", c.refs());
        m.put("lane", c.lane());
        return m;
    }

    static Map<String, Object> commitDetailBody(RepoViewReader.CommitDetail d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sha", d.sha());
        m.put("message", d.message());
        m.put("author", d.author());
        m.put("author_email", d.authorEmail());
        m.put("author_date", d.authorDate());
        m.put("committer_date", d.committerDate());
        m.put("parents", d.parents());
        m.put("refs", d.refs());
        return m;
    }

    /* ─── project-only pieces ─── */

    private Map<String, Object> authInfo(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", p.authRepo());
        m.put("target_ref", p.targetRef());
        // 权威库 tip：目录不存在时直接算「不存在」，不去猜一个可能误导的落后判断。
        String tip = p.authRepo() != null && !p.authRepo().isBlank()
                && Files.isDirectory(Path.of(p.authRepo()))
                ? reader.resolveRefTip(Path.of(p.authRepo()), p.targetRef())
                : null;
        m.put("tip", tip);
        return m;
    }

    private Project requireProject(String projectId) {
        return projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
    }
}
