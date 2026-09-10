package gate.web.service;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.store.TicketRepository;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 存储设置数据面（设置中心「存储设置」页）：数据目录事实、可清理缓存类别、
 * 工作区存储管理与「在系统中打开」动作。无任何落库状态，全部由生效的
 * {@link GateConfig} 路径推导。
 *
 * <p>清理范围刻意收紧为<b>非证据</b>的一次性区域（进程临时日志、git 临时目录、适配器
 * 诊断日志）与工作区内<b>可再生</b>的依赖/构建产物（node_modules、target 等）：blob
 * 存储 / 索引目录 / 审计日志承载快照与判决证据链，绝不作为可清理项下发；工单克隆
 * 的源代码与 .git 同样不可清理，只有可再生目录可以整树删除。
 */
public final class StorageInfoService {

    /** 单个目录统计的文件数上限（占用只是估算值；防超大树拖死请求）。 */
    private static final int SIZE_WALK_FILE_CAP = 200_000;

    /**
     * 工作区内视为「可再生」的目录名（依赖与构建产物，删后可由包管理器/构建工具
     * 重新生成）。命中即整树统计并纳入 prunable 清单；嵌套命中（如 monorepo 里
     * packages 子包下的 node_modules）被外层整树覆盖，不重复单列。
     */
    private static final Set<String> REGENERABLE_DIRS = Set.of(
            "node_modules", "target", "dist", "build", "out",
            ".next", ".nuxt", ".turbo", ".parcel-cache", ".cache",
            "coverage", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache");

    /** 工作区目录名的合法形态：字母数字开头，仅字母数字点横下划线（防路径穿越）。 */
    private static final Pattern WORKSPACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** .git 目录：占用计入工作区总量，但不参与「最后改动」统计，也不可清理。 */
    private static final String GIT_DIR = ".git";

    /**
     * 「在系统中打开目录」的执行缝：生产实现按 OS 拉起文件管理器；测试注入记录器，
     * 绝不真的弹窗口。open 失败统一抛 {@link GateException}（USAGE/GATE_ERROR_IO）。
     */
    public interface DirLauncher {
        void open(Path dir) throws Exception;
    }

    /** 可清理缓存类别：dir = 删目录内文件（保留目录本身）；truncate = 单文件清空。 */
    private record CacheCategory(String id, Path path, boolean truncate) {
    }

    /** 目录/文件占用统计（bytes/files 为累计值；approx=因文件数封顶或 IO 失败而是下界估计）。 */
    private record SizeStat(long bytes, long files, boolean approx) {

        static final SizeStat MISSING = new SizeStat(0, 0, false);
    }

    /** 工作区扫描结果：总占用、最后改动时间与可再生目录清单（可再生占用单列）。 */
    private record WorkspaceScan(long bytes, long files, boolean approx, Long lastActiveMs,
                                 List<Map<String, Object>> prunable, long prunableBytes,
                                 boolean prunableApprox) {
    }

    /** 整树删除的实绩（bytes/files = 删除成功的常规文件；dirs = 删除成功的目录数）。 */
    private record TreeRemoved(long bytes, long files, long dirs) {
    }

    private final Path gateHome;
    private final Path clonesRoot;
    private final Path dbPath;
    private final Path blobRoot;
    private final Path auditPath;
    private final Path gateToml;
    private final TicketRepository tickets;
    private final DirLauncher launcher;

    public StorageInfoService(GateConfig config, Path gateToml, TicketRepository tickets) {
        this(config, gateToml, tickets, defaultLauncher());
    }

    /** 测试缝：注入工单仓库与目录打开器。 */
    StorageInfoService(GateConfig config, Path gateToml, TicketRepository tickets, DirLauncher launcher) {
        this.gateHome = config.gateHome().toAbsolutePath().normalize();
        this.clonesRoot = config.clonesRoot().toAbsolutePath().normalize();
        this.dbPath = config.dbPath().toAbsolutePath().normalize();
        this.blobRoot = config.blobRoot().toAbsolutePath().normalize();
        this.auditPath = config.auditPath().toAbsolutePath().normalize();
        this.gateToml = gateToml == null ? null : gateToml.toAbsolutePath().normalize();
        this.tickets = tickets;
        this.launcher = launcher;
    }

    /**
     * 生产目录打开器：Windows explorer / macOS open / Linux xdg-open。
     * explorer.exe 在成功拉起窗口时也常返回非零退出码，因此 Windows 路径只验证进程能
     * 拉起、不校验退出码；其余平台等待短超时并检查退出码。无桌面的环境（容器/服务器）
     * 会以 IOException 失败，由调用方映射为可读错误提示。
     */
    static DirLauncher defaultLauncher() {
        return dir -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> cmd;
            boolean windows;
            if (os.contains("win")) {
                cmd = List.of("explorer", dir.toString());
                windows = true;
            } else if (os.contains("mac") || os.contains("darwin")) {
                cmd = List.of("open", dir.toString());
                windows = false;
            } else {
                cmd = List.of("xdg-open", dir.toString());
                windows = false;
            }
            Process process = new ProcessBuilder(cmd).start();
            if (windows) {
                return; // fire-and-forget：explorer 拉起即视为成功（退出码不可信）
            }
            boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (exited && process.exitValue() != 0) {
                throw new IOException(cmd.get(0) + " exited with " + process.exitValue());
            }
        };
    }

    /** 数据目录概览：配置文件 + 五个核心数据位置，各带估算占用与「可打开」标记。 */
    public Map<String, Object> overview() {
        List<Map<String, Object>> dirs = new ArrayList<>();
        dirs.add(dirInfo("gate_home", gateHome, true));
        dirs.add(dirInfo("clones_root", clonesRoot, true));
        dirs.add(dirInfo("db", dbPath, false));
        dirs.add(dirInfo("blob_root", blobRoot, false));
        dirs.add(dirInfo("audit", auditPath, false));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toml_path", gateToml == null ? null : gateToml.toString());
        body.put("dirs", dirs);
        return body;
    }

    /** 可清理缓存清单（含占用与文件数；占用与清理是两次独立快照，前端清理后需重取）。 */
    public Map<String, Object> caches() {
        List<Map<String, Object>> caches = new ArrayList<>();
        for (CacheCategory c : cacheCategories()) {
            SizeStat st = statOf(c.path());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("path", c.path().toString());
            m.put("bytes", st.bytes());
            m.put("files", st.files());
            m.put("approx", st.approx());
            caches.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caches", caches);
        return body;
    }

    /** 按类别清理：返回实际释放的字节数与删除的文件数。未知 id 拒绝（fail-closed）。 */
    public Map<String, Object> clean(String id) {
        CacheCategory category = cacheCategories().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE, "unknown cache category: " + id));
        long removedBytes = 0;
        long removedFiles = 0;
        if (category.truncate()) {
            if (Files.isRegularFile(category.path())) {
                try {
                    removedBytes = Files.size(category.path());
                    Files.write(category.path(), new byte[0]);
                    // 与 statOf 同口径：0 字节文件不计文件数（已是空日志时 clean 幂等归零）
                    removedFiles = removedBytes > 0 ? 1 : 0;
                } catch (IOException e) {
                    throw new GateException(GateErrorCode.GATE_ERROR_IO,
                            "cannot truncate " + category.path(), e);
                }
            }
        } else {
            long[] removed = deleteFilesUnder(category.path());
            removedBytes = removed[0];
            removedFiles = removed[1];
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("removed_bytes", removedBytes);
        body.put("removed_files", removedFiles);
        return body;
    }

    /**
     * 工作区存储清单（设置中心「工作区存储管理」分区）：克隆根下每个工作区的占用、
     * 最后改动时间与可再生目录占用，并按目录名关联工单（标题/项目）。
     * 统计与清理是两次独立快照，前端清理后需重取。
     */
    public Map<String, Object> workspaces() {
        List<Map<String, Object>> items = new ArrayList<>();
        if (Files.isDirectory(clonesRoot)) {
            try (Stream<Path> stream = Files.list(clonesRoot)) {
                for (Path dir : stream.sorted().toList()) {
                    if (!Files.isDirectory(dir)) {
                        continue; // 克隆根下的散落文件不是工作区
                    }
                    items.add(workspaceInfo(dir));
                }
            } catch (IOException e) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "cannot list clones root " + clonesRoot, e);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clones_root", clonesRoot.toString());
        body.put("workspaces", items);
        return body;
    }

    /**
     * 清理指定工作区的全部可再生目录（node_modules/构建产物等，整树删除）。
     * 源代码、.git 与不可再生的其余内容绝不触碰。未知/非法 id 拒绝（fail-closed）。
     */
    public Map<String, Object> pruneWorkspace(String id) {
        Path root = workspaceDir(id);
        WorkspaceScan scan = scanWorkspace(root);
        long removedBytes = 0;
        long removedFiles = 0;
        long removedDirs = 0;
        List<String> removed = new ArrayList<>();
        for (Map<String, Object> p : scan.prunable()) {
            // name 是扫描时下发的相对路径（正斜杠），只可能命中可再生名单，绝不指向别处
            Path dir = root.resolve(p.get("name").toString());
            TreeRemoved t = deleteTree(dir);
            removedBytes += t.bytes();
            removedFiles += t.files();
            removedDirs += t.dirs();
            if (t.dirs() > 0 || t.files() > 0) {
                removed.add(p.get("name").toString());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("removed_bytes", removedBytes);
        body.put("removed_files", removedFiles);
        body.put("removed_dirs", removedDirs);
        body.put("dirs", removed);
        return body;
    }

    /** 工作区目录解析：id 必须匹配合法形态且是克隆根的直接子目录（防路径穿越）。 */
    private Path workspaceDir(String id) {
        if (id == null || !WORKSPACE_ID.matcher(id).matches()) {
            throw new GateException(GateErrorCode.USAGE, "invalid workspace id: " + id);
        }
        Path dir = clonesRoot.resolve(id).normalize();
        if (!dir.getParent().equals(clonesRoot) || !Files.isDirectory(dir)) {
            throw new GateException(GateErrorCode.USAGE, "unknown workspace: " + id);
        }
        return dir;
    }

    private Map<String, Object> workspaceInfo(Path dir) {
        String id = dir.getFileName().toString();
        WorkspaceScan scan = scanWorkspace(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("path", dir.toString());
        m.put("bytes", scan.bytes());
        m.put("approx", scan.approx());
        m.put("last_active_ms", scan.lastActiveMs());
        m.put("ticket", ticketInfo(id));
        m.put("prunable", scan.prunable());
        m.put("prunable_bytes", scan.prunableBytes());
        m.put("prunable_approx", scan.prunableApprox());
        return m;
    }

    /** 按目录名关联工单：返回标题与所属项目；无仓库或查无此工单时为 null。 */
    private Map<String, Object> ticketInfo(String id) {
        if (tickets == null) {
            return null;
        }
        return tickets.find(id).map(t -> {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("title", t.title());
            tm.put("project_id", t.projectId());
            return tm;
        }).orElse(null);
    }

    /**
     * 单次遍历统计一个工作区：占用、最后改动与可再生目录清单。
     * 「最后改动」只统计工作文件（.git 与可再生目录内部的 mtime 不算——重装依赖、
     * 拉取对象库不代表工作内容有变化）；可再生目录整树统计占用后剪枝不深入。
     */
    private WorkspaceScan scanWorkspace(Path root) {
        long[] bytes = {0};
        long[] files = {0};
        boolean[] approx = {false};
        long[] lastActiveMs = {Long.MIN_VALUE};
        boolean[] hasMtime = {false};
        long[] prunableBytes = {0};
        boolean[] prunableApprox = {false};
        List<Map<String, Object>> prunable = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    boolean regenerable = REGENERABLE_DIRS.contains(name);
                    if (regenerable || GIT_DIR.equals(name)) {
                        // 整树统计占用后剪枝；占用封顶口径与 statOf 一致（下界估计）
                        SizeStat st = statOf(dir);
                        bytes[0] += st.bytes();
                        files[0] += st.files();
                        if (st.approx()) {
                            approx[0] = true;
                        }
                        if (regenerable) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", root.relativize(dir).toString().replace('\\', '/'));
                            m.put("bytes", st.bytes());
                            m.put("files", st.files());
                            m.put("approx", st.approx());
                            prunable.add(m);
                            prunableBytes[0] += st.bytes();
                            if (st.approx()) {
                                prunableApprox[0] = true;
                            }
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (files[0] >= SIZE_WALK_FILE_CAP) {
                        approx[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    bytes[0] += attrs.size();
                    files[0]++;
                    long mtime = attrs.lastModifiedTime().toMillis();
                    if (mtime > lastActiveMs[0]) {
                        lastActiveMs[0] = mtime;
                        hasMtime[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    approx[0] = true;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            approx[0] = true;
        }
        return new WorkspaceScan(bytes[0], files[0], approx[0],
                hasMtime[0] ? lastActiveMs[0] : null,
                prunable, prunableBytes[0], prunableApprox[0]);
    }

    /** 整树删除（子先于父；目录与文件全删）；单个条目删除失败跳过并继续。 */
    private static TreeRemoved deleteTree(Path dir) {
        long bytes = 0;
        long files = 0;
        long dirs = 0;
        if (!Files.exists(dir)) {
            return new TreeRemoved(0, 0, 0);
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            var iterator = stream.sorted(Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                boolean isDir = Files.isDirectory(entry);
                try {
                    long size = isDir ? 0 : Files.size(entry);
                    Files.delete(entry);
                    if (isDir) {
                        dirs++;
                    } else {
                        // 只有删除成功才计入释放量：占用中删除失败的文件绝不虚报
                        bytes += size;
                        files++;
                    }
                } catch (IOException ignored) {
                    // 文件被占用（如 IDE/构建进程正持有 node_modules）：跳过，绝不中断整批清理
                }
            }
        } catch (Exception ignored) {
            // 遍历失败按「已尽力」返回
        }
        return new TreeRemoved(bytes, files, dirs);
    }

    /** 在系统文件管理器中打开数据目录；target 只接受 overview 下发的 openable key。 */
    public void open(String target) {
        Path dir = switch (target == null ? "" : target) {
            case "gate_home" -> gateHome;
            case "clones_root" -> clonesRoot;
            default -> throw new GateException(GateErrorCode.USAGE, "unknown open target: " + target);
        };
        try {
            Files.createDirectories(dir);
            launcher.open(dir);
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot open directory in system file manager: " + e.getMessage(), e);
        }
    }

    /** 三个可清理类别，路径与 GateRuntime / ProcessRunnerImpl / AdapterLog 的实际落点一致。 */
    private List<CacheCategory> cacheCategories() {
        return List.of(
                new CacheCategory("proc_temp", gateHome.resolve("proc"), false),
                new CacheCategory("gate_tmp", gateHome.resolve("tmp"), false),
                new CacheCategory("adapters_log", gateHome.resolve("adapters.log"), true));
    }

    private Map<String, Object> dirInfo(String key, Path path, boolean openable) {
        SizeStat st = statOf(path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("path", path.toString());
        m.put("bytes", st.bytes());
        m.put("exists", Files.exists(path));
        m.put("approx", st.approx());
        m.put("openable", openable);
        return m;
    }

    /** 估算目录/文件占用：文件数封顶逐个累加，超顶或 IO 失败标记 approx（下界估计）。 */
    private static SizeStat statOf(Path path) {
        if (path == null || !Files.exists(path)) {
            return SizeStat.MISSING;
        }
        if (Files.isRegularFile(path)) {
            try {
                long size = Files.size(path);
                // 0 字节文件不计文件数：截断类日志（adapters_log）清理后 files=0，
                // 前端「无可清理内容」的禁用判定才能生效，不会被无限重复点击。
                return new SizeStat(size, size > 0 ? 1 : 0, false);
            } catch (IOException e) {
                return new SizeStat(0, 0, true);
            }
        }
        long bytes = 0;
        long files = 0;
        boolean approx = false;
        try (Stream<Path> stream = Files.walk(path)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (files >= SIZE_WALK_FILE_CAP) {
                    approx = true;
                    break;
                }
                Path entry = iterator.next();
                if (Files.isRegularFile(entry)) {
                    try {
                        bytes += Files.size(entry);
                        files++;
                    } catch (IOException ignored) {
                        approx = true;
                    }
                }
            }
        } catch (Exception e) {
            approx = true;
        }
        return new SizeStat(bytes, files, approx);
    }

    /** 删除目录内的全部常规文件（递归，保留目录结构）；单个文件删除失败跳过并继续。 */
    private static long[] deleteFilesUnder(Path dir) {
        long bytes = 0;
        long files = 0;
        if (!Files.isDirectory(dir)) {
            return new long[]{0, 0};
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                try {
                    long size = Files.size(entry);
                    Files.delete(entry);
                    // 只有删除成功才计入释放量：占用中删除失败的文件绝不虚报
                    bytes += size;
                    files++;
                } catch (IOException ignored) {
                    // 文件被占用（如正在写入的进程日志）：跳过，绝不中断整批清理
                }
            }
        } catch (Exception ignored) {
            // 遍历失败按「已尽力」返回
        }
        return new long[]{bytes, files};
    }
}
