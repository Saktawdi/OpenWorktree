package gate.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.config.GateConfig;
import gate.domain.error.GateException;
import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 存储设置数据面（T-116）：目录概览、缓存清单、按类清理与「在系统中打开」。
 *
 * <p>目录打开器走注入的记录器缝，测试绝不真的拉起文件管理器；清理断言走真实临时
 * 文件系统（进程临时日志、git 临时目录、适配器日志三种类别逐一验证）。
 */
class StorageInfoServiceTest {

    @TempDir
    Path dir;

    private Path gateHome;
    private Path clonesRoot;
    private StorageInfoService service;
    private final List<Path> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateHome = dir.resolve("gate-home");
        clonesRoot = dir.resolve("clones");
        service = new StorageInfoService(config(), null, opened::add);
    }

    private GateConfig config() {
        return new GateConfig(
                2, "storage-test",
                dir.resolve("auth.git"),
                clonesRoot,
                List.of("refs/heads/main"),
                gateHome,
                gateHome.resolve("approvals"),
                gateHome.resolve("gate.db"),
                gateHome.resolve("blobs"),
                gateHome.resolve("audit.jsonl"),
                gateHome.resolve("locks"),
                gateHome.resolve("idx"),
                new CommitIdentity("gate", "gate@localhost", "1700000000 +0000"),
                Policy.defaults(),
                null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dirOf(Map<String, Object> body, String key) {
        for (Map<String, Object> d : (List<Map<String, Object>>) body.get("dirs")) {
            if (key.equals(d.get("key"))) {
                return d;
            }
        }
        throw new AssertionError("dir not found: " + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cacheOf(Map<String, Object> body, String id) {
        for (Map<String, Object> c : (List<Map<String, Object>>) body.get("caches")) {
            if (id.equals(c.get("id"))) {
                return c;
            }
        }
        throw new AssertionError("cache not found: " + id);
    }

    @Test
    void overviewEmitsConfiguredPathsWithSizes() throws IOException {
        Files.createDirectories(gateHome.resolve("blobs"));
        Files.writeString(gateHome.resolve("gate.db"), "12345", StandardCharsets.UTF_8);
        Files.writeString(gateHome.resolve("blobs").resolve("a.bin"), "abcdefgh");

        Map<String, Object> body = service.overview();

        assertNull(body.get("toml_path"), "未提供 toml 路径时透传 null");

        Map<String, Object> home = dirOf(body, "gate_home");
        assertEquals(gateHome.toAbsolutePath().normalize().toString(), home.get("path"));
        assertEquals(true, home.get("openable"));
        assertEquals(13L, home.get("bytes")); // gate.db(5) + blobs/a.bin(8)

        Map<String, Object> clones = dirOf(body, "clones_root");
        assertEquals(clonesRoot.toAbsolutePath().normalize().toString(), clones.get("path"));
        assertEquals(true, clones.get("openable"));
        assertEquals(0L, clones.get("bytes"));

        Map<String, Object> db = dirOf(body, "db");
        assertEquals(5L, db.get("bytes"));
        assertEquals(false, db.get("openable"));
        assertEquals(true, db.get("exists"));

        Map<String, Object> audit = dirOf(body, "audit");
        assertEquals(false, audit.get("exists"));
        assertEquals(false, audit.get("openable"));
    }

    @Test
    void cachesListsTheThreeCleanableCategories() {
        Map<String, Object> body = service.caches();
        cacheOf(body, "proc_temp");
        cacheOf(body, "gate_tmp");
        cacheOf(body, "adapters_log");
    }

    @Test
    void cleanProcTempDeletesFilesAndKeepsDirectory() throws IOException {
        Path proc = gateHome.resolve("proc");
        Files.createDirectories(proc);
        Files.writeString(proc.resolve("proc-out-1.log"), "abcdef");
        Files.writeString(proc.resolve("proc-err-1.log"), "xy");

        Map<String, Object> result = service.clean("proc_temp");
        assertEquals(true, result.get("ok"));
        assertEquals(8L, result.get("removed_bytes"));
        assertEquals(2L, result.get("removed_files"));
        assertFalse(Files.exists(proc.resolve("proc-out-1.log")));
        assertTrue(Files.isDirectory(proc), "目录本身保留，后续进程照常落临时文件");
    }

    @Test
    void cleanCountsOnlySuccessfullyDeletedFiles() throws IOException {
        // 持有打开句柄模拟「文件被占用」（Windows 上锁定，POSIX 上仍可删除）：
        // 释放量必须只统计真正删除成功的文件，删除失败的文件绝不虚报字节。
        Path proc = gateHome.resolve("proc");
        Files.createDirectories(proc);
        Files.writeString(proc.resolve("locked.log"), "12345");
        Path free = proc.resolve("free.log");
        Files.writeString(free, "abcdef");
        try (java.nio.channels.FileChannel lock =
                     java.nio.channels.FileChannel.open(proc.resolve("locked.log"),
                             java.nio.file.StandardOpenOption.WRITE)) {
            lock.lock();
            Map<String, Object> result = service.clean("proc_temp");

            long removedBytes = ((Number) result.get("removed_bytes")).longValue();
            long removedFiles = ((Number) result.get("removed_files")).longValue();
            if (Files.exists(proc.resolve("locked.log"))) {
                // Windows：锁定文件删除失败，释放量必须恰好等于删除成功的那一个
                assertEquals(6L, removedBytes, "删除失败的文件不得计入释放字节");
                assertEquals(1L, removedFiles);
            } else {
                // POSIX：句柄不阻止删除，两个都成功
                assertEquals(11L, removedBytes);
                assertEquals(2L, removedFiles);
            }
        }
    }

    @Test
    void cleanGateTmpClearsNestedContents() throws IOException {
        Path tmp = gateHome.resolve("tmp");
        Files.createDirectories(tmp.resolve("nested"));
        Files.writeString(tmp.resolve("nested").resolve("work.bin"), "12345678");

        Map<String, Object> result = service.clean("gate_tmp");
        assertEquals(8L, result.get("removed_bytes"));
        assertEquals(1L, result.get("removed_files"));
        assertFalse(Files.exists(tmp.resolve("nested").resolve("work.bin")));
    }

    @Test
    void cleanAdaptersLogTruncatesInsteadOfDeleting() throws IOException {
        Files.createDirectories(gateHome);
        Path log = gateHome.resolve("adapters.log");
        Files.writeString(log, "{\"ts\":\"x\"}\n{\"ts\":\"y\"}\n", StandardCharsets.UTF_8);

        Map<String, Object> result = service.clean("adapters_log");
        assertEquals(true, result.get("ok"));
        assertEquals((long) "{\"ts\":\"x\"}\n{\"ts\":\"y\"}\n".length(), result.get("removed_bytes"));
        assertEquals(1L, result.get("removed_files"));
        assertTrue(Files.isRegularFile(log), "日志文件保留（截断清零），适配器无需重建句柄");
        assertEquals(0, Files.size(log));

        // 截断清零后的再次统计：0 字节日志 files=0（前端据此禁用清理按钮），clean 也幂等归零
        Map<String, Object> caches = service.caches();
        assertEquals(0L, cacheOf(caches, "adapters_log").get("files"));
        Map<String, Object> again = service.clean("adapters_log");
        assertEquals(0L, again.get("removed_bytes"));
        assertEquals(0L, again.get("removed_files"));
    }

    @Test
    void cleanUnknownCategoryIsRejected() {
        GateException e = assertThrows(GateException.class, () -> service.clean("blob_store"));
        assertEquals(gate.domain.error.GateErrorCode.USAGE, e.code());
    }

    @Test
    void cleanMissingCategoryIsANoOp() {
        Map<String, Object> result = service.clean("proc_temp");
        assertEquals(0L, result.get("removed_bytes"));
        assertEquals(0L, result.get("removed_files"));
    }

    @Test
    void openUsesLauncherOnlyForKnownTargets() {
        service.open("gate_home");
        service.open("clones_root");
        assertEquals(2, opened.size());
        assertEquals(gateHome, opened.get(0));

        GateException e = assertThrows(GateException.class, () -> service.open("db"));
        assertEquals(gate.domain.error.GateErrorCode.USAGE, e.code());
        assertEquals(2, opened.size(), "未知 target 不得触达启动器");
    }
}
