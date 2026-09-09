package gate.web.service;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 存储设置数据面（设置中心「存储设置」页）：数据目录事实、可清理缓存类别与
 * 「在系统中打开」动作。无任何落库状态，全部由生效的 {@link GateConfig} 路径推导。
 *
 * <p>清理范围刻意收紧为<b>非证据</b>的一次性区域（进程临时日志、git 临时目录、适配器
 * 诊断日志）：blob 存储 / 索引目录 / 审计日志承载快照与判决证据链，绝不作为可清理项
 * 下发；工单克隆是用户工作区，同样不可清理。
 */
public final class StorageInfoService {

    /** 单个目录统计的文件数上限（占用只是估算值；防超大树拖死请求）。 */
    private static final int SIZE_WALK_FILE_CAP = 200_000;

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

    private final Path gateHome;
    private final Path clonesRoot;
    private final Path dbPath;
    private final Path blobRoot;
    private final Path auditPath;
    private final Path gateToml;
    private final DirLauncher launcher;

    public StorageInfoService(GateConfig config, Path gateToml) {
        this(config, gateToml, defaultLauncher());
    }

    /** 测试缝：注入目录打开器。 */
    StorageInfoService(GateConfig config, Path gateToml, DirLauncher launcher) {
        this.gateHome = config.gateHome().toAbsolutePath().normalize();
        this.clonesRoot = config.clonesRoot().toAbsolutePath().normalize();
        this.dbPath = config.dbPath().toAbsolutePath().normalize();
        this.blobRoot = config.blobRoot().toAbsolutePath().normalize();
        this.auditPath = config.auditPath().toAbsolutePath().normalize();
        this.gateToml = gateToml == null ? null : gateToml.toAbsolutePath().normalize();
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
