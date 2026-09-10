package gate.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import gate.domain.session.AgentCli;
import gate.domain.session.Session;
import gate.domain.session.SessionStatus;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.SessionRepository;
import gate.ports.store.TicketRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 存储设置数据面（T-116）：目录概览、缓存清单、按类清理与「在系统中打开」。
 *
 * <p>目录打开器走注入的记录器缝，测试绝不真的拉起文件管理器；清理断言走真实临时
 * 文件系统（进程临时日志、git 临时目录、适配器日志三种类别逐一验证；工作区可再生
 * 目录整树删除与路径穿越拒绝同样落盘验证）。
 */
class StorageInfoServiceTest {

    @TempDir
    Path dir;

    private Path gateHome;
    private Path clonesRoot;
    private FakeTickets tickets;
    private StorageInfoService service;
    private final List<Path> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateHome = dir.resolve("gate-home");
        clonesRoot = dir.resolve("clones");
        tickets = new FakeTickets();
        service = new StorageInfoService(config(), null, tickets, opened::add);
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

    /** 工单仓库替身：只有 find 有行为，其余方法绝不参与本服务链路。 */
    private static final class FakeTickets implements TicketRepository {

        private final Map<String, Ticket> byNo = new HashMap<>();

        void put(String no, String title, String projectId) {
            byNo.put(no, new Ticket(no, title, "refs/heads/master", null, null, null, null, null,
                    TicketStage.IN_PROGRESS, Instant.EPOCH, Instant.EPOCH,
                    null, null, null, null, projectId, null, null, List.of(), false));
        }

        @Override
        public void insert(Ticket ticket) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Ticket> find(String ticketNo) {
            return Optional.ofNullable(byNo.get(ticketNo));
        }

        @Override
        public void updateStage(String ticketNo, TicketStage stage, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateExecTokens(String ticketNo, long totalTokens, String source, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Ticket> findByStage(TicketStage stage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Ticket> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Ticket> findSuperByProject(String projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateSuperLocation(String ticketNo, String clonePath, String targetRef, Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workspaceOf(Map<String, Object> body, String id) {
        for (Map<String, Object> w : (List<Map<String, Object>>) body.get("workspaces")) {
            if (id.equals(w.get("id"))) {
                return w;
            }
        }
        throw new AssertionError("workspace not found: " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prunableOf(Map<String, Object> workspace, String name) {
        for (Map<String, Object> p : (List<Map<String, Object>>) workspace.get("prunable")) {
            if (name.equals(p.get("name"))) {
                return p;
            }
        }
        throw new AssertionError("prunable not found: " + name);
    }

    /** 造一个典型工作区：源文件（10 天前）+ node_modules（含嵌套）+ target + .git。 */
    private Path seedWorkspace(String id) throws IOException {
        Path root = Files.createDirectories(clonesRoot.resolve(id));
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("node_modules").resolve("packages").resolve("app")
                .resolve("node_modules"));
        Files.createDirectories(root.resolve("target").resolve("classes"));
        Files.createDirectories(root.resolve(".git").resolve("objects"));
        Files.writeString(root.resolve("src").resolve("Main.java"), "hello", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("node_modules").resolve("lib.js"), "abcdef", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("node_modules").resolve("packages").resolve("app")
                .resolve("node_modules").resolve("inner.js"), "z", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("target").resolve("classes").resolve("A.class"), "xy", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".git").resolve("objects").resolve("o"), "1234", StandardCharsets.UTF_8);
        return root;
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

    /* ─── 工作区存储管理 ─── */

    @Test
    void workspacesListOccupancyPrunableAndTicketAssociation() throws IOException {
        seedWorkspace("T-116");
        tickets.put("T-116", "存储设置分区", "openworktree");

        Map<String, Object> body = service.workspaces();
        assertEquals(clonesRoot.toAbsolutePath().normalize().toString(), body.get("clones_root"));
        Map<String, Object> ws = workspaceOf(body, "T-116");

        // 总占用 = src(5) + node_modules(6+1) + target(2) + .git(4)
        assertEquals(18L, ws.get("bytes"));
        assertEquals(Boolean.FALSE, ws.get("approx"));

        // 可再生清单：node_modules 与 target（嵌套 node_modules 被外层整树覆盖，不重复单列）
        assertEquals(7L, prunableOf(ws, "node_modules").get("bytes"), "嵌套内层文件计入外层占用");
        assertEquals(2L, prunableOf(ws, "target").get("bytes"));
        assertEquals(9L, ((Number) ws.get("prunable_bytes")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prunable = (List<Map<String, Object>>) ws.get("prunable");
        assertEquals(2, prunable.size(), "嵌套可再生目录不得重复单列");

        // 工单关联：标题与项目透传
        @SuppressWarnings("unchecked")
        Map<String, Object> ticket = (Map<String, Object>) ws.get("ticket");
        assertEquals("存储设置分区", ticket.get("title"));
        assertEquals("openworktree", ticket.get("project_id"));
    }

    @Test
    void lastActiveExcludesRegenerableAndGitContent() throws IOException {
        Path root = seedWorkspace("T-116");
        // 源文件时间锚在 10 天前；node_modules/.git 留在「现在」——重装依赖或 git 操作
        // 不得把「最后改动」刷新，只有工作文件的 mtime 参与统计
        FileTime tenDaysAgo = FileTime.fromMillis(System.currentTimeMillis() - 10L * 24 * 3600 * 1000);
        Files.setLastModifiedTime(root.resolve("src").resolve("Main.java"), tenDaysAgo);

        Map<String, Object> ws = workspaceOf(service.workspaces(), "T-116");
        long lastActive = ((Number) ws.get("last_active_ms")).longValue();
        long now = System.currentTimeMillis();
        assertTrue(lastActive <= now - 9L * 24 * 3600 * 1000, "node_modules/.git 的 mtime 不算最后改动");
        assertTrue(lastActive >= now - 11L * 24 * 3600 * 1000, "源文件 mtime 是唯一的最后改动来源");
    }

    @Test
    void unassociatedWorkspaceAndMissingRepoEmitNullTicket() throws IOException {
        seedWorkspace("GHOST-1"); // 库里没有这个工单
        StorageInfoService noRepo = new StorageInfoService(config(), null, null, opened::add);

        assertNull(workspaceOf(service.workspaces(), "GHOST-1").get("ticket"), "查无工单 → null");
        assertNull(workspaceOf(noRepo.workspaces(), "GHOST-1").get("ticket"), "无工单仓库 → null");
    }

    @Test
    void workspacesSkipLooseFilesAndMissingClonesRoot() throws IOException {
        Files.createDirectories(clonesRoot);
        Files.writeString(clonesRoot.resolve("stray.txt"), "x", StandardCharsets.UTF_8);
        Map<String, Object> body = service.workspaces();
        assertTrue(((List<?>) body.get("workspaces")).isEmpty(), "克隆根下的散文件不是工作区");

        // clonesRoot 从未创建：安静返回空清单而不是失败
        GateConfig missing = new GateConfig(
                2, "storage-test",
                dir.resolve("auth.git"),
                dir.resolve("clones-missing"),
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
        assertTrue(((List<?>) new StorageInfoService(missing, null, tickets, opened::add)
                .workspaces().get("workspaces")).isEmpty());
    }

    @Test
    void pruneDeletesRegenerableTreesOnly() throws IOException {
        seedWorkspace("T-116");

        Map<String, Object> result = service.pruneWorkspace("T-116");

        assertEquals(true, result.get("ok"));
        assertEquals(9L, ((Number) result.get("removed_bytes")).longValue());
        assertEquals(3L, ((Number) result.get("removed_files")).longValue());
        assertTrue(((Number) result.get("removed_dirs")).longValue() >= 4, "node_modules/target 及其子目录");
        @SuppressWarnings("unchecked")
        List<String> dirs = (List<String>) result.get("dirs");
        assertTrue(dirs.contains("node_modules"));
        assertTrue(dirs.contains("target"));

        assertFalse(Files.exists(clonesRoot.resolve("T-116").resolve("node_modules")));
        assertFalse(Files.exists(clonesRoot.resolve("T-116").resolve("target")));
        assertTrue(Files.exists(clonesRoot.resolve("T-116").resolve("src").resolve("Main.java")),
                "源代码绝不清理");
        assertTrue(Files.exists(clonesRoot.resolve("T-116").resolve(".git").resolve("objects").resolve("o")),
                ".git 绝不清理");
    }

    @Test
    void pruneEmptyWorkspaceIsANoOp() throws IOException {
        Files.createDirectories(clonesRoot.resolve("EMPTY-1"));
        Map<String, Object> result = service.pruneWorkspace("EMPTY-1");
        assertEquals(true, result.get("ok"));
        assertEquals(0L, ((Number) result.get("removed_bytes")).longValue());
        assertEquals(0L, ((Number) result.get("removed_dirs")).longValue());
        assertTrue(((List<?>) result.get("dirs")).isEmpty());
    }

    @Test
    void pruneRejectsInvalidOrUnknownIds() throws IOException {
        seedWorkspace("T-116");
        for (String bad : new String[]{"..", "../..", "a/b", "C:\\x", ".git", "", null}) {
            GateException e = assertThrows(GateException.class, () -> service.pruneWorkspace(bad),
                    "id=" + bad);
            assertEquals(GateErrorCode.USAGE, e.code(), "id=" + bad);
        }
        // 合法形态但目录不存在同样拒绝
        GateException e = assertThrows(GateException.class, () -> service.pruneWorkspace("T-999"));
        assertEquals(GateErrorCode.USAGE, e.code());
        // 校验失败绝不动真格删除
        assertTrue(Files.exists(clonesRoot.resolve("T-116").resolve("node_modules")));
    }

    /* ─── 一键清理（会话运行边界） ─── */

    @Test
    void pruneAllCleansEveryWorkspaceInOneTraversal() throws IOException {
        seedWorkspace("T-104");
        seedWorkspace("T-116");

        Map<String, Object> result = service.pruneAllWorkspaces();

        assertEquals(true, result.get("ok"));
        // 每个工作区各 node_modules(7) + target(2) = 9，两个工作区合计 18 / 6 个文件
        assertEquals(18L, ((Number) result.get("removed_bytes")).longValue());
        assertEquals(6L, ((Number) result.get("removed_files")).longValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> per = (List<Map<String, Object>>) result.get("workspaces");
        assertEquals(List.of("T-104", "T-116"), per.stream().map(m -> m.get("id")).toList(),
                "按工作区给出分项（稳定排序）");
        @SuppressWarnings("unchecked")
        List<String> dirs = (List<String>) per.get(0).get("dirs");
        assertTrue(dirs.contains("node_modules"));
        assertTrue(dirs.contains("target"));

        for (String id : List.of("T-104", "T-116")) {
            Path root = clonesRoot.resolve(id);
            assertFalse(Files.exists(root.resolve("node_modules")));
            assertFalse(Files.exists(root.resolve("target")));
            assertTrue(Files.exists(root.resolve("src").resolve("Main.java")), "源代码绝不清理");
            assertTrue(Files.exists(root.resolve(".git").resolve("objects").resolve("o")), ".git 绝不清理");
        }
    }

    @Test
    void pruneAllSkipsWorkspacesWithoutRegenerableContent() throws IOException {
        seedWorkspace("T-104");
        Files.createDirectories(clonesRoot.resolve("EMPTY-1").resolve("src"));

        Map<String, Object> result = service.pruneAllWorkspaces();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> per = (List<Map<String, Object>>) result.get("workspaces");
        assertEquals(1, per.size());
        assertEquals("T-104", per.get(0).get("id"), "无内容的工作区不进分项");
    }

    @Test
    void pruneAllRefusesWhileAnySessionRunsAndDeletesNothing() throws IOException {
        seedWorkspace("T-116");
        StorageInfoService guarded = guarded(new BusySessions("s-1"), new FakeSessions());

        GateException e = assertThrows(GateException.class, guarded::pruneAllWorkspaces);
        assertEquals(GateErrorCode.USAGE, e.code());
        assertTrue(e.getMessage().contains("s-1"), "拒绝信息列出运行中的会话 id");
        assertTrue(Files.exists(clonesRoot.resolve("T-116").resolve("node_modules")),
                "有会话在运行时绝不删任何文件");
    }

    @Test
    void pruneWorkspaceRefusesOnlyTheWorkspaceWithRunningSessions() throws IOException {
        seedWorkspace("T-116");
        seedWorkspace("T-110");
        // 运行中会话 s-1 绑定 T-116：该工作区拒绝，别的工作区照常可清理
        StorageInfoService guarded = guarded(new BusySessions("s-1"), new FakeSessions("s-1", "T-116"));

        GateException e = assertThrows(GateException.class, () -> guarded.pruneWorkspace("T-116"));
        assertEquals(GateErrorCode.USAGE, e.code());
        assertTrue(Files.exists(clonesRoot.resolve("T-116").resolve("node_modules")));

        Map<String, Object> ok = guarded.pruneWorkspace("T-110");
        assertEquals(9L, ((Number) ok.get("removed_bytes")).longValue());
        assertFalse(Files.exists(clonesRoot.resolve("T-110").resolve("node_modules")));
    }

    @Test
    void pruneAllWithoutClonesRootIsANoOp() {
        Map<String, Object> result = service.pruneAllWorkspaces();
        assertEquals(true, result.get("ok"));
        assertEquals(0L, ((Number) result.get("removed_bytes")).longValue());
        assertTrue(((List<?>) result.get("workspaces")).isEmpty());
    }

    /** 装配了会话端口的服务替身（清理边界链路的受测对象）。 */
    private StorageInfoService guarded(AgentSessionPort sessions, SessionRepository sessionRepo) {
        return new StorageInfoService(config(), null, tickets, sessions, sessionRepo, opened::add);
    }

    /** 会话端口替身：只有 busySessionIds 有行为，其余方法绝不参与本服务链路。 */
    private static final class BusySessions implements AgentSessionPort {

        private final Set<String> busy;

        BusySessions(String... ids) {
            this.busy = Set.of(ids);
        }

        @Override
        public Set<String> busySessionIds() {
            return busy;
        }

        @Override
        public Session start(StartRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sendMessage(SendRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<gate.domain.session.SessionMessage> getHistory(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<SessionEvent> streamEvents(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AutoCloseable attachListener(String sessionId,
                                            Consumer<gate.domain.session.SessionStreamChunk> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void respondPermission(String sessionId, String permissionId, String response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<gate.domain.session.PermissionRequest> pendingPermissions(String sessionId) {
            throw new UnsupportedOperationException();
        }
    }

    /** 会话仓库替身：只回答「会话属于哪个工单」，其余方法绝不参与本服务链路。 */
    private static final class FakeSessions implements SessionRepository {

        private final Map<String, String> ticketBySession = new HashMap<>();

        /** 成对传入 sessionId, ticketNo。 */
        FakeSessions(String... sessionTicketPairs) {
            for (int i = 0; i < sessionTicketPairs.length; i += 2) {
                ticketBySession.put(sessionTicketPairs[i], sessionTicketPairs[i + 1]);
            }
        }

        @Override
        public Optional<Session> find(String id) {
            String ticketNo = ticketBySession.get(id);
            if (ticketNo == null) {
                return Optional.empty();
            }
            return Optional.of(new Session(id, ticketNo, "cfg", AgentCli.CLAUDE,
                    SessionStatus.ACTIVE, null, "clones/" + ticketNo, 0, Instant.EPOCH, Instant.EPOCH,
                    null, null, false));
        }

        @Override
        public List<Session> findByTicket(String ticketNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Session> findByAgentConfig(String agentConfigId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertMessage(gate.domain.session.SessionMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<gate.domain.session.SessionMessage> findMessages(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteMessages(String sessionId) {
            throw new UnsupportedOperationException();
        }
    }
}
