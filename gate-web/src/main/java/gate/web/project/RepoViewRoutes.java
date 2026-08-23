package gate.web.project;

import gate.adapters.git.GitCli;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.ports.ProcessRunner;
import gate.ports.ProjectRepository;
import gate.web.ApiRoutes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 项目 → 仓库视图 (web console): read-only rendering data for a project's workspace repository —
 * the branch/commit graph and the file tree with per-entry last-commit attribution.
 *
 * <p>Reads the workspace repo the project was registered from (never the auth repo: the console
 * mirrors what the human sees in their working copy). Everything is plain {@code git} plumbing via
 * {@link GitCli} (ADR-1: no JGit), read-only, and bounded so a huge repository cannot stall the
 * console: at most {@value #MAX_COMMITS} commits across {@value #MAX_BRANCHES} branches, and
 * {@value #MAX_ENTRIES} entries per directory listing.
 *
 * <p>Lane assignment is the classic first-free-slot algorithm: lanes are seeded from the branch
 * tips (HEAD's branch first), then commits are processed in {@code --topo-order}; a commit takes
 * the first lane expecting it, its first parent inherits that slot, and extra parents of a merge
 * fan out into free slots. That reproduces the gitgraph-style drawing the UI renders.
 */
public final class RepoViewRoutes {

    private static final int MAX_COMMITS = 100;
    private static final int MAX_BRANCHES = 20;
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_LOG_WALK = 2000;
    private static final int MAX_TREE_DEPTH = 32;
    private static final String FIELD_SEP = "%x01";
    private static final char RECORD_SEP = '\u0001';
    private static final char HEADER_SEP = '\u0002';

    private final ProjectRepository projects;
    private final GitCli git;

    public RepoViewRoutes(ProjectRepository projects, GitCli git) {
        this.projects = projects;
        this.git = git;
    }

    /** GET /api/projects/{id}/repo — branches + topo-ordered commits with lane numbers. */
    public ApiRoutes.Response repoView(String projectId) {
        Project p = requireProject(projectId);
        Path ws = Path.of(p.workspacePath());
        requireGitWorkspace(ws);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.put("repo_path", ws.toString());

        String head = resolveHead(ws);
        body.put("head", head);

        List<Branch> branches = readBranches(ws, head, p.targetRef());
        Map<String, List<String>> branchTips = new LinkedHashMap<>();
        for (Branch b : branches) {
            branchTips.computeIfAbsent(b.tip, k -> new ArrayList<>()).add(b.name);
        }
        Map<String, List<String>> tagTips = readTagTips(ws);

        List<Commit> commits = readCommits(ws);
        assignLanes(branches, commits);

        List<Map<String, Object>> branchRows = new ArrayList<>();
        for (Branch b : branches) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name);
            m.put("tip", b.tip);
            m.put("lane", b.lane);
            branchRows.add(m);
        }
        List<Map<String, Object>> commitRows = new ArrayList<>();
        for (Commit c : commits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sha", c.sha);
            m.put("parents", c.parents);
            m.put("message", c.message);
            m.put("author", c.author);
            m.put("time", c.time);
            List<String> refs = new ArrayList<>();
            branchTips.getOrDefault(c.sha, List.of()).forEach(refs::add);
            for (String tag : tagTips.getOrDefault(c.sha, List.of())) {
                refs.add("tag: " + tag);
            }
            m.put("refs", refs);
            m.put("lane", c.lane);
            commitRows.add(m);
        }
        body.put("branches", branchRows);
        body.put("commits", commitRows);
        body.put("truncated", commits.size() >= MAX_COMMITS);
        return new ApiRoutes.Response(200, body);
    }

    /**
     * GET /api/projects/{id}/tree[/{path…}] — direct children of one directory in HEAD, each with
     * the short sha and subject of the last commit that touched it. Children are listed on demand
     * so the tree UI can expand lazily without the backend walking the whole repository.
     */
    public ApiRoutes.Response treeView(String projectId, List<String> pathSegments) {
        Project p = requireProject(projectId);
        Path ws = Path.of(p.workspacePath());
        requireGitWorkspace(ws);
        String dir = normalizeDir(pathSegments);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", p.id());
        body.put("path", dir);

        String head = revParseHead(ws);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (head != null) {
            List<Entry> entries = listEntries(ws, head, dir);
            attributeLastCommits(ws, head, dir, entries);
            entries.sort(Comparator
                    .comparing((Entry e) -> e.type.equals("dir") ? 0 : 1)
                    .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
            for (Entry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", e.path);
                m.put("type", e.type);
                m.put("size", e.type.equals("file") ? e.size : null);
                m.put("last_commit_short", e.lastShort);
                m.put("last_message", e.lastMessage);
                rows.add(m);
            }
        }
        body.put("entries", rows);
        return new ApiRoutes.Response(200, body);
    }

    /* ─── repo graph pieces ─── */

    private record Branch(String name, String tip, int lane) {
    }

    /** Parsed commit before lane assignment. */
    private static final class Commit {
        final String sha;
        final List<String> parents;
        final String message;
        final String author;
        final String time;
        int lane = -1;

        Commit(String sha, List<String> parents, String message, String author, String time) {
            this.sha = sha;
            this.parents = parents;
            this.message = message;
            this.author = author;
            this.time = time;
        }
    }

    private Project requireProject(String projectId) {
        return projects.find(projectId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such project: " + projectId));
    }

    private static void requireGitWorkspace(Path ws) {
        if (!Files.isDirectory(ws) || !Files.exists(ws.resolve(".git"))) {
            throw new GateException(GateErrorCode.USAGE,
                    "workspace is not a git repository: " + ws);
        }
    }

    /** HEAD as a ref name when on a branch, else the raw sha (detached); null when unborn. */
    private String resolveHead(Path ws) {
        ProcessRunner.ProcRun sym = git.run(ws, Map.of(), "symbolic-ref", "-q", "HEAD");
        if (sym.ok() && !sym.stdout().isBlank()) {
            return sym.stdout().trim();
        }
        String sha = revParseHead(ws);
        return sha;
    }

    private String revParseHead(Path ws) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(), "rev-parse", "--verify", "HEAD");
        return run.ok() ? run.stdout().trim() : null;
    }

    /**
     * Branch tips in seeding order: HEAD's branch, then the gate target ref, then newest first.
     * (Space separator: for-each-ref has no {@code %xNN} escapes, and ref names can never
     * contain a space.)
     */
    private List<Branch> readBranches(Path ws, String head, String targetRef) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "for-each-ref", "--sort=-committerdate",
                "--format=%(refname) %(objectname)", "refs/heads");
        Set<String> order = new LinkedHashSet<>();
        if (head != null && head.startsWith("refs/heads/")) {
            order.add(head);
        }
        if (targetRef != null && targetRef.startsWith("refs/heads/")) {
            order.add(targetRef);
        }
        List<String[]> parsed = new ArrayList<>();
        if (run.ok()) {
            for (String line : run.stdout().split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(" ");
                if (parts.length != 2) continue;
                parsed.add(parts);
                order.add(parts[0]);
            }
        }
        List<Branch> branches = new ArrayList<>();
        List<String> orderedNames = order.stream().limit(MAX_BRANCHES).toList();
        for (String name : orderedNames) {
            for (String[] parts : parsed) {
                if (parts[0].equals(name)) {
                    branches.add(new Branch(name, parts[1], branches.size()));
                    break;
                }
            }
        }
        return branches;
    }

    /** Peeled tag tips: sha → tag short names (annotated tags resolve to their commit). */
    private Map<String, List<String>> readTagTips(Path ws) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "for-each-ref",
                "--format=%(refname) %(if)%(*objectname)%(then)%(*objectname)%(else)%(objectname)%(end)",
                "refs/tags");
        Map<String, List<String>> tips = new LinkedHashMap<>();
        if (run.ok()) {
            for (String line : run.stdout().split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(" ");
                if (parts.length != 2) continue;
                String shortName = parts[0].substring("refs/tags/".length());
                tips.computeIfAbsent(parts[1], k -> new ArrayList<>()).add(shortName);
            }
        }
        return tips;
    }

    private List<Commit> readCommits(Path ws) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "log", "--branches", "--topo-order", "--date=iso-strict",
                "-n", String.valueOf(MAX_COMMITS),
                "--pretty=format:%H" + FIELD_SEP + "%P" + FIELD_SEP + "%an"
                        + FIELD_SEP + "%aI" + FIELD_SEP + "%s");
        List<Commit> commits = new ArrayList<>();
        if (!run.ok()) {
            return commits;
        }
        for (String line : run.stdout().split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split(String.valueOf(RECORD_SEP));
            if (f.length < 5) continue;
            List<String> parents = new ArrayList<>();
            for (String p : f[1].split(" ")) {
                if (!p.isBlank()) parents.add(p);
            }
            commits.add(new Commit(f[0], parents, f[4], f[2], f[3]));
        }
        return commits;
    }

    /**
     * First-free-slot lane assignment. Lanes hold the sha each slot expects next; a commit takes
     * the first lane expecting it (or a free slot), the slot then expects its first parent, and
     * merge parents fan out into further free slots.
     */
    private static void assignLanes(List<Branch> branches, List<Commit> commits) {
        List<String> lanes = new ArrayList<>();
        for (Branch b : branches) {
            int slot = freeSlot(lanes);
            lanes.set(slot, b.tip);
        }
        for (Commit c : commits) {
            int lane = lanes.indexOf(c.sha);
            if (lane < 0) {
                lane = freeSlot(lanes);
            }
            c.lane = lane;
            if (c.parents.isEmpty()) {
                lanes.set(lane, null);
            } else {
                lanes.set(lane, c.parents.get(0));
                for (int i = 1; i < c.parents.size(); i++) {
                    String parent = c.parents.get(i);
                    if (!lanes.contains(parent)) {
                        lanes.set(freeSlot(lanes), parent);
                    }
                }
            }
        }
        Map<String, Integer> commitLanes = new LinkedHashMap<>();
        for (Commit c : commits) {
            commitLanes.putIfAbsent(c.sha, c.lane);
        }
        for (int i = 0; i < branches.size(); i++) {
            Branch b = branches.get(i);
            Integer lane = commitLanes.get(b.tip);
            branches.set(i, new Branch(b.name, b.tip, lane != null ? lane : b.lane));
        }
    }

    private static int freeSlot(List<String> lanes) {
        for (int i = 0; i < lanes.size(); i++) {
            if (lanes.get(i) == null) {
                return i;
            }
        }
        lanes.add(null);
        return lanes.size() - 1;
    }

    /* ─── file tree pieces ─── */

    private static final class Entry {
        final String name;
        final String path;
        final String type;
        Long size;
        String lastShort = "";
        String lastMessage = "";
        long bestTime = Long.MIN_VALUE;

        Entry(String name, String path, String type) {
            this.name = name;
            this.path = path;
            this.type = type;
        }
    }

    /** Joins and validates URL path segments; rejects anything that could escape the work tree. */
    private static String normalizeDir(List<String> segments) {
        List<String> clean = new ArrayList<>();
        for (String seg : segments) {
            if (seg == null || seg.isEmpty() || seg.equals(".") || seg.equals("..")
                    || seg.contains("\\") || seg.contains("\u0000")) {
                throw new GateException(GateErrorCode.USAGE, "invalid tree path segment");
            }
            clean.add(seg);
        }
        if (clean.size() > MAX_TREE_DEPTH) {
            throw new GateException(GateErrorCode.USAGE, "tree path too deep");
        }
        return String.join("/", clean);
    }

    /** Direct children of {@code dir} in HEAD's tree via {@code ls-tree <head>:<dir>}. */
    private List<Entry> listEntries(Path ws, String head, String dir) {
        String treeish = dir.isEmpty() ? head : head + ":" + dir;
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "-c", "core.quotepath=false", "ls-tree", "-l", treeish);
        List<Entry> entries = new ArrayList<>();
        if (!run.ok()) {
            return entries;
        }
        for (String line : run.stdout().split("\n")) {
            if (line.isBlank()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String name = line.substring(tab + 1).trim();
            String[] meta = line.substring(0, tab).trim().split("\\s+");
            if (meta.length < 3 || name.isEmpty()) continue;
            // ls-tree reports git object types; the console contract is dir/file.
            String type = meta[1];
            if (!type.equals("tree") && !type.equals("blob")) continue;
            Entry e = new Entry(name, dir.isEmpty() ? name : dir + "/" + name,
                    type.equals("tree") ? "dir" : "file");
            if (type.equals("blob") && meta.length >= 4 && !meta[3].equals("-")) {
                try {
                    e.size = Long.parseLong(meta[3]);
                } catch (NumberFormatException ignored) {
                    // size column unusable — the UI treats it as unknown.
                }
            }
            entries.add(e);
            if (entries.size() >= MAX_ENTRIES) break;
        }
        return entries;
    }

    /**
     * Attributes each entry the newest commit touching it: one {@code git log --name-only} walk
     * over the directory (bounded by {@value #MAX_LOG_WALK}), stop early once every entry is
     * covered. A dir is attributed by any path under it.
     *
     * <p>Ordering: the walk is {@code --topo-order}, so a descendant always precedes its
     * ancestors even when commits share a timestamp (the common case in fast local runs) —
     * first hit wins for ties. A strictly newer commit re-attributes, which repairs the
     * parallel-branch case where topo order interleaves unrelated lines.
     */
    private void attributeLastCommits(Path ws, String head, String dir, List<Entry> entries) {
        if (entries.isEmpty()) return;
        List<String> argv = new ArrayList<>(List.of(
                "-c", "core.quotepath=false", "log", "-n", String.valueOf(MAX_LOG_WALK),
                "--topo-order", "--name-only",
                "--pretty=format:%x01%h%x02%aI%x02%s", head));
        if (!dir.isEmpty()) {
            argv.add("--");
            argv.add(dir + "/");
        }
        ProcessRunner.ProcRun run = git.run(ws, Map.of(), argv.toArray(new String[0]));
        if (!run.ok()) return;

        int uncovered = entries.size();
        String shortSha = null;
        String subject = null;
        long commitTime = Long.MIN_VALUE;
        for (String line : run.stdout().split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) == RECORD_SEP) {
                int sep1 = line.indexOf(HEADER_SEP);
                int sep2 = sep1 < 0 ? -1 : line.indexOf(HEADER_SEP, sep1 + 1);
                if (sep2 < 0) continue;
                shortSha = line.substring(1, sep1);
                commitTime = parseEpochMillis(line.substring(sep1 + 1, sep2));
                subject = line.substring(sep2 + 1);
                continue;
            }
            if (shortSha == null) continue;
            String changed = line.trim();
            if (changed.isEmpty()) continue;
            for (Entry e : entries) {
                boolean touched = changed.equals(e.path)
                        || (e.type.equals("dir") && changed.startsWith(e.path + "/"));
                if (touched && (e.lastShort.isEmpty() || commitTime > e.bestTime)) {
                    if (e.lastShort.isEmpty()) uncovered--;
                    e.lastShort = shortSha;
                    e.lastMessage = subject == null ? "" : subject;
                    e.bestTime = commitTime;
                }
            }
            if (uncovered == 0) return;
        }
    }

    private static long parseEpochMillis(String iso) {
        try {
            return java.time.Instant.parse(iso.trim()).toEpochMilli();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }
}
