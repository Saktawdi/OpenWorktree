package gate.adapters.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.domain.session.TurnPart;
import gate.ports.store.SessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V24 session_task journal：upsert（读-改-写语义的事件累积结果）、findTasks 的 lazy
 * 回填（存量会话无行时按 parts 全量重放）、findToolParts 的 rowid 序事件源、
 * 删除会话 FK 级联清理、permission_mode 列的持久化往返。
 */
class SessionTaskJournalTest {

    @TempDir
    Path dir;

    private JdbcTemplate jdbc;
    private SessionRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dir.resolve("clone").resolve(".git"));
        DataSource ds = SqliteDataSourceFactory.create(dir.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        jdbc = new JdbcTemplate(ds);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO provider(id, name, base_url, api_key_ref, type, created_at, updated_at) "
                + "VALUES ('manual','manual','local://manual','none','manual',?,?)", now.toString(), now.toString());
        jdbc.update("INSERT INTO agent_config(id, name, cli, provider_id, model, created_at, updated_at) "
                + "VALUES ('cfg','C','CLAUDE','manual','m',?,?)", now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path, executor_provider_id,
                                   executor_model, reviewer_provider_id, reviewer_model, stage,
                                   created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, "T-TASK", "t", "refs/heads/main", dir.resolve("clone").toString(),
                null, null, null, null, "IN_PROGRESS", Instant.now().toString(), Instant.now().toString());
        repo = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(dir.resolve("blobs")));
    }

    private String newSession(String id, String permissionMode) {
        Session s = new Session(id, "T-TASK", "cfg", AgentCli.CLAUDE, SessionStatus.ACTIVE,
                null, dir.resolve("clone").toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, "s", false, null, null, null, false, permissionMode);
        repo.insert(s);
        return id;
    }

    private void assistantWithParts(String sessionId, TurnPart... parts) {
        repo.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
                "reply", List.of(), null, false, Instant.now(), List.of(parts)));
    }

    @Test
    void upsert_overwrites_journal_and_emptyArrayMeansEmptyList() {
        String sid = newSession("s1", null);
        assertTrue(repo.findTasks(sid).isEmpty(), "no row before any write");

        repo.upsertTasks(sid, "[{\"id\":1,\"subject\":\"a\",\"status\":\"pending\"}]");
        assertTrue(repo.findTasks(sid).orElseThrow().contains("\"id\":1"));

        repo.upsertTasks(sid, "[]");
        assertEquals("[]", repo.findTasks(sid).orElseThrow());
    }

    @Test
    void lazy_backfill_replays_task_events_from_parts_history() {
        // V24 之前的存量 claude 会话：无 journal 行，但 parts 历史里有任务事件——
        // 首次读取时全量重放落行（确定性事件源），此后永久走 journal。
        String sid = newSession("s2", null);
        assistantWithParts(sid,
                TurnPart.tool("TaskCreate", "{\"subject\":\"first\"}", "Task #1 created successfully: first"),
                TurnPart.tool("TaskUpdate", "{\"taskId\":\"1\",\"status\":\"in_progress\"}",
                        "Updated task #1 status"));
        // 两条消息（rowid 序），第二条里再建一个任务：跨消息重放保持时序。
        assistantWithParts(sid,
                TurnPart.tool("TaskCreate", "{\"subject\":\"second\"}", "Task #2 created successfully: second"));

        String journal = repo.findTasks(sid).orElseThrow();
        assertTrue(journal.contains("\"id\":1"), journal);
        assertTrue(journal.contains("\"id\":2"), journal);
        assertTrue(journal.contains("\"status\":\"in_progress\""), journal);
        // 回填已落行：第二次读取直接命中（结果稳定）。
        assertEquals(journal, repo.findTasks(sid).orElseThrow());
    }

    @Test
    void findToolParts_returns_tool_segments_in_rowid_order() {
        String sid = newSession("s3", null);
        assistantWithParts(sid, TurnPart.thinking("hmm"), TurnPart.tool("Bash", "{\"cmd\":\"ls\"}", "ok"));
        assistantWithParts(sid, TurnPart.tool("Read", "{\"file\":\"a\"}", "content"));

        List<TurnPart> tools = repo.findToolParts(sid);
        assertEquals(2, tools.size(), "thinking/text segments are excluded");
        assertEquals("Bash", tools.get(0).name());
        assertEquals("Read", tools.get(1).name());

        // 无任务事件的会话：findTasks 不落行（lazy 回填区分「无 journal」与「空 journal」）。
        assertTrue(repo.findTasks(sid).isEmpty(), "no task events → no journal row");
    }

    @Test
    void deleting_session_cascades_task_row() {
        String sid = newSession("s4", null);
        repo.upsertTasks(sid, "[{\"id\":1,\"subject\":\"a\",\"status\":\"pending\"}]");
        assertTrue(repo.findTasks(sid).isPresent());
        repo.deleteMessages(sid);
        repo.delete(sid);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_task WHERE session_id = ?", Integer.class, sid));
    }

    @Test
    void permission_mode_roundtrips_through_insert_and_update() {
        String sid = newSession("s5", "bypassPermissions");
        assertEquals("bypassPermissions", repo.find(sid).orElseThrow().permissionMode());

        repo.update(repo.find(sid).orElseThrow().withPermissionMode("plan"));
        assertEquals("plan", repo.find(sid).orElseThrow().permissionMode());

        // null 清除：回退语义由 buildArgv 兜底（acceptEdits）。
        repo.update(repo.find(sid).orElseThrow().withPermissionMode(null));
        assertTrue(repo.find(sid).orElseThrow().permissionMode() == null,
                "null permission mode must persist as null (acceptEdits fallback)");
    }
}
