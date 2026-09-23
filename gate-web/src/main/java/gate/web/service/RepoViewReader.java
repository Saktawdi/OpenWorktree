package gate.web.service;

import gate.adapters.git.GitCli;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.ProcessRunner;
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
import java.util.regex.Pattern;

/**
 * 只读 git 仓库渲染数据读取（web console）:分支/提交图（带泳道号）、目录树（带最后提交归属）、
 * 单提交详情。
 *
 * <p>项目仓库视图与工单仓库视图共用本类——两者只差「仓库路径从哪来」（项目工作区 vs 工单克隆），
 * git 读取与泳道算法完全一致，避免同一套 plumbing 写两遍后各自漂移。所有读取都是真实 {@code git}
 * 二进制的只读调用（ADR-1：没有 JGit），并且有上限保护：最多 {@value #MAX_COMMITS} 条提交 /
 * {@value #MAX_BRANCHES} 个分支 / {@value #MAX_ENTRIES} 个目录项 / {@value #MAX_REFS} 个引用，
 * 巨型仓库不会把控制台拖死。
 *
 * <p>泳道分配是经典的 first-free-slot 算法：泳道先由分支 tip 播种（HEAD 所在分支优先），随后按
 * {@code --topo-order} 处理提交——提交占领第一个等它的泳道，其第一父继承该槽位，合并的额外父
 * fan 到空闲槽位。这正是 UI 画出的 gitgraph 式连线。
 */
public final class RepoViewReader {

    private static final int MAX_COMMITS = 100;
    private static final int MAX_BRANCHES = 20;
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_REFS = 50;
    private static final int MAX_LOG_WALK = 2000;
    private static final int MAX_TREE_DEPTH = 32;

    private static final String FIELD_SEP = "%x01";
    private static final char RECORD_SEP = '\u0001';
    private static final char HEADER_SEP = '\u0002';

    /** 只接受十六进制缩写/完整对象名：挡掉 "--upload-pack=…" 这类会被 git 当成选项的入参。 */
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{4,64}");

    private final GitCli git;

    public RepoViewReader(GitCli git) {
        this.git = git;
    }

    /** 分支（泳道号已按提交位形校正）。 */
    public record Branch(String name, String tip, int lane) {
    }

    /** 提交：message 为单行 subject，refs 为挂在它上面的分支/tag（tag 带 "tag: " 前缀）。 */
    public record Commit(String sha, List<String> parents, String message, String author,
            String time, List<String> refs, int lane) {
    }

    /** 分支 + 提交图：UI「分支图 · 提交历史」一次渲染所需的全量数据。 */
    public record RepoGraph(String head, List<Branch> branches, List<Commit> commits,
            boolean truncated) {

        static RepoGraph empty(String head) {
            return new RepoGraph(head, List.of(), List.of(), false);
        }
    }

    /** 目录树条目（带最后改它的提交的短 sha 与 subject）。 */
    public record TreeEntry(String path, String type, Long size, String lastCommitShort,
            String lastMessage) {
    }

    /** 单提交详情：完整 message（含 body）与提交元信息。 */
    public record CommitDetail(String sha, String message, String author, String authorEmail,
            String authorDate, String committerDate, List<String> parents, List<String> refs) {
    }

    /** 仓库根守卫：目录存在且带 {@code .git}（worktree 的 .git 是文件，同样成立）。 */
    public static void requireGitWorkspace(Path ws) {
        if (!Files.isDirectory(ws) || !Files.exists(ws.resolve(".git"))) {
            throw new GateException(GateErrorCode.USAGE,
                    "workspace is not a git repository: " + ws);
        }
    }

    /** HEAD 符号引用（unborn 时也是分支名），detach 时退回对象 id。 */
    public String resolveHead(Path ws) {
        ProcessRunner.ProcRun sym = git.run(ws, Map.of(), "symbolic-ref", "-q", "HEAD");
        if (sym.ok() && !sym.stdout().isBlank()) {
            return sym.stdout().trim();
        }
        return revParseHead(ws);
    }

    /** HEAD 对象 id；unborn（尚无任何提交）时为 null。 */
    private String revParseHead(Path ws) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(), "rev-parse", "--verify", "HEAD");
        return run.ok() ? run.stdout().trim() : null;
    }

    /**
     * 任意引用在指定仓库里的 tip 对象 id（引用不存在或仓库不可读时为 null）。
     *
     * <p>供「权威库 vs 工作区」落后判断使用：调用方自己拿着权威库路径，不经过本类的仓库解析。
     */
    public String resolveRefTip(Path repo, String ref) {
        if (repo == null || ref == null || ref.isBlank()) {
            return null;
        }
        ProcessRunner.ProcRun run = git.run(repo, Map.of(), "rev-parse", "--verify", ref);
        return run.ok() && !run.stdout().isBlank() ? run.stdout().trim() : null;
    }

    /**
     * 读分支 + 提交图。{@code targetRef} 只影响分支泳道播种顺序（目标分支尽量占 0 号泳道），
     * 传 null 表示无目标分支。
     */
    public RepoGraph readGraph(Path ws, String targetRef) {
        requireGitWorkspace(ws);
        String head = resolveHead(ws);
        List<Branch> branches = readBranches(ws, head, targetRef);
        List<RawCommit> raw = readCommits(ws);
        if (branches.isEmpty() && raw.isEmpty()) {
            // 空仓库（unborn HEAD）不是错误：控制台画空图。
            return RepoGraph.empty(head);
        }
        assignLanes(branches, raw);
        Map<String, List<String>> branchTips = new LinkedHashMap<>();
        for (Branch b : branches) {
            branchTips.computeIfAbsent(b.tip(), k -> new ArrayList<>()).add(b.name());
        }
        Map<String, List<String>> tagTips = readTagTips(ws);
        List<Commit> commits = new ArrayList<>();
        for (RawCommit c : raw) {
            List<String> refs = new ArrayList<>();
            for (String b : branchTips.getOrDefault(c.sha, List.of())) {
                refs.add(b);
            }
            for (String tag : tagTips.getOrDefault(c.sha, List.of())) {
                refs.add("tag: " + tag);
            }
            commits.add(new Commit(c.sha, c.parents, c.message, c.author, c.time, refs, c.lane));
        }
        return new RepoGraph(head, branches, commits, commits.size() >= MAX_COMMITS);
    }

    /** 读一层目录（HEAD 视角），{@code dir} 为空串 = 根目录。 */
    public List<TreeEntry> readTree(Path ws, String dir) {
        requireGitWorkspace(ws);
        String head = revParseHead(ws);
        // unborn HEAD（尚无任何提交）不是错误：目录树就是空列表。
        List<RawEntry> entries = head == null ? new ArrayList<>() : listEntries(ws, head, dir);
        attributeLastCommits(ws, head, dir, entries);
        // 目录在前、同类按名字（忽略大小写）——与既有项目仓库视图的排序口径一致。
        entries.sort(Comparator
                .comparing((RawEntry e) -> e.type.equals("dir") ? 0 : 1)
                .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        List<TreeEntry> rows = new ArrayList<>();
        for (RawEntry e : entries) {
            rows.add(new TreeEntry(e.path, e.type, e.size, e.lastShort, e.lastMessage));
        }
        return rows;
    }

    /**
     * 读单提交详情（完整 message）。{@code sha} 必须是十六进制对象名（4..64 位），否则拒绝——
     * 这个值直接进 git argv，不能放过任何会被当成选项的入参。
     */
    public CommitDetail readCommit(Path ws, String sha) {
        requireGitWorkspace(ws);
        String ref = sha == null ? "" : sha.trim();
        if (!SHA.matcher(ref).matches()) {
            throw new GateException(GateErrorCode.USAGE, "invalid commit id: " + ref);
        }
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "show", "-s", "--date=iso-strict",
                "--format=%H" + FIELD_SEP + "%P" + FIELD_SEP + "%an" + FIELD_SEP + "%ae"
                        + FIELD_SEP + "%aI" + FIELD_SEP + "%cI" + FIELD_SEP + "%B",
                ref);
        if (!run.ok()) {
            throw new GateException(GateErrorCode.USAGE, "no such commit: " + ref);
        }
        // %B 是多行且放在最后：stdout 按 \u0001 切成至多 7 段，最后一段即完整 message
        // （git 会在末尾补一个换行，去掉尾部空白即可，body 内的换行原样保留）。
        String[] f = run.stdout().split(String.valueOf(RECORD_SEP), 7);
        if (f.length < 7) {
            throw new GateException(GateErrorCode.USAGE, "unreadable commit: " + ref);
        }
        List<String> parents = new ArrayList<>();
        for (String p : f[1].split(" ")) {
            if (!p.isBlank()) {
                parents.add(p);
            }
        }
        return new CommitDetail(f[0], f[6].stripTrailing(), f[2], f[3], f[4], f[5],
                parents, readRefsContaining(ws, f[0]));
    }

    /** 目录路径段清洗：拒绝空段、".."、反斜杠与 NUL，并限深。 */
    public static String normalizeDir(List<String> segments) {
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

    /* ─── repo graph pieces ─── */

    /** 内部可变提交（泳道与引用在算完后回填），对外只暴露不可变 record。 */
    private static final class RawCommit {
        final String sha;
        final List<String> parents;
        final String message;
        final String author;
        final String time;
        int lane = -1;

        RawCommit(String sha, List<String> parents, String message, String author, String time) {
            this.sha = sha;
            this.parents = parents;
            this.message = message;
            this.author = author;
            this.time = time;
        }
    }

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

    private List<RawCommit> readCommits(Path ws) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "log", "--branches", "--topo-order", "--date=iso-strict",
                "-n", String.valueOf(MAX_COMMITS),
                "--pretty=format:%H" + FIELD_SEP + "%P" + FIELD_SEP + "%an"
                        + FIELD_SEP + "%aI" + FIELD_SEP + "%s");
        List<RawCommit> commits = new ArrayList<>();
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
            commits.add(new RawCommit(f[0], parents, f[4], f[2], f[3]));
        }
        return commits;
    }

    /** 引用包含该提交的分支/tag（详情页用；分支给全名，tag 用 "tag: " 前缀，与图上一致）。 */
    private List<String> readRefsContaining(Path ws, String sha) {
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "for-each-ref", "--contains=" + sha, "--format=%(refname)",
                "refs/heads", "refs/tags");
        List<String> refs = new ArrayList<>();
        if (run.ok()) {
            for (String line : run.stdout().split("\n")) {
                if (line.isBlank()) continue;
                String name = line.trim();
                if (name.startsWith("refs/tags/")) {
                    refs.add("tag: " + name.substring("refs/tags/".length()));
                } else {
                    refs.add(name);
                }
                if (refs.size() >= MAX_REFS) break;
            }
        }
        return refs;
    }

    private static void assignLanes(List<Branch> branches, List<RawCommit> commits) {
        List<String> lanes = new ArrayList<>();
        for (Branch b : branches) {
            int slot = freeSlot(lanes);
            lanes.set(slot, b.tip());
        }
        for (RawCommit c : commits) {
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
        for (RawCommit c : commits) {
            commitLanes.putIfAbsent(c.sha, c.lane);
        }
        for (int i = 0; i < branches.size(); i++) {
            Branch b = branches.get(i);
            Integer lane = commitLanes.get(b.tip());
            branches.set(i, new Branch(b.name(), b.tip(), lane != null ? lane : b.lane()));
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

    private static final class RawEntry {
        final String name;
        final String path;
        final String type;
        Long size;
        String lastShort = "";
        String lastMessage = "";
        long bestTime = Long.MIN_VALUE;

        RawEntry(String name, String path, String type) {
            this.name = name;
            this.path = path;
            this.type = type;
        }
    }

    private List<RawEntry> listEntries(Path ws, String head, String dir) {
        String treeish = dir.isEmpty() ? head : head + ":" + dir;
        ProcessRunner.ProcRun run = git.run(ws, Map.of(),
                "-c", "core.quotepath=false", "ls-tree", "-l", treeish);
        List<RawEntry> entries = new ArrayList<>();
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
            String type = meta[1];
            if (!type.equals("tree") && !type.equals("blob")) continue;
            RawEntry e = new RawEntry(name, dir.isEmpty() ? name : dir + "/" + name,
                    type.equals("tree") ? "dir" : "file");
            if (type.equals("blob") && meta.length >= 4 && !meta[3].equals("-")) {
                try {
                    e.size = Long.parseLong(meta[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            entries.add(e);
            if (entries.size() >= MAX_ENTRIES) break;
        }
        return entries;
    }

    private void attributeLastCommits(Path ws, String head, String dir, List<RawEntry> entries) {
        if (entries.isEmpty() || head == null) return;
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
            for (RawEntry e : entries) {
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
