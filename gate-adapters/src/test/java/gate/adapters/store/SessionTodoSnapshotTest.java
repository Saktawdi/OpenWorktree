package gate.adapters.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.domain.session.ToolCall;
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
 * V21 session_todo 快照：upsert（last-write-wins，"[]"=显式清空）、findTodos 的 lazy
 * 回填（存量会话无行时扫历史取最后一条合法 todowrite 落行）、删除会话 FK 级联清理。
 */
class SessionTodoSnapshotTest {

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
                + "VALUES ('cfg','C','OPENCODE','manual','m',?,?)", now.toString(), now.toString());
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path, executor_provider_id,
                                   executor_model, reviewer_provider_id, reviewer_model, stage,
                                   created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, "T-TODO", "t", "refs/heads/main", dir.resolve("clone").toString(),
                null, null, null, null, "IN_PROGRESS", Instant.now().toString(), Instant.now().toString());
        repo = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(dir.resolve("blobs")));
    }

    private String newSession(String id) {
        repo.insert(new Session(id, "T-TODO", "cfg", AgentCli.OPENCODE, SessionStatus.ACTIVE,
                null, dir.resolve("clone").toString(), -1, Instant.now(), null,
                SessionUsage.EMPTY, "s", false, null, null, null, false, null));
        return id;
    }

    private void assistantMessage(String sessionId, ToolCall... calls) {
        repo.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
                "reply", List.of(calls), null, false, Instant.now(), List.of()));
    }

    @Test
    void upsert_overwrites_and_empty_array_means_cleared() {
        String sid = newSession("s1");
        assertTrue(repo.findTodos(sid).isEmpty(), "no row before any write");

        repo.upsertTodos(sid, "[{\"content\":\"a\",\"status\":\"pending\"}]");
        assertTrue(repo.findTodos(sid).orElseThrow().contains("a"));

        // 空数组 = 显式清空：行仍在（todos_json="[]"），findTodos 返回该行而非 empty。
        repo.upsertTodos(sid, "[]");
        assertEquals("[]", repo.findTodos(sid).orElseThrow());
    }

    @Test
    void lazy_backfill_reads_latest_todowrite_from_history() {
        String sid = newSession("s2");
        String stale = "[{\"content\":\"stale\",\"status\":\"in_progress\"}]";
        String latest = "[{\"content\":\"fresh\",\"status\":\"completed\"}]";
        // 双层 JSON：tool_calls_blob 数组里的 arguments_json 是内嵌 JSON 字符串。
        assistantMessage(sid, new ToolCall("todowrite", stale, null));
        assistantMessage(sid, new ToolCall("bash", "{\"command\":\"ls\"}", null));
        assistantMessage(sid, new ToolCall("todowrite", latest, null));

        var todos = repo.findTodos(sid);
        assertTrue(todos.isPresent());
        assertTrue(todos.orElseThrow().contains("fresh"), todos.orElseThrow());
        // 回填已落行：第二次读取直接命中快照行（消息已不可见地参与判定，结果稳定）。
        assertEquals(todos.orElseThrow(), repo.findTodos(sid).orElseThrow());
    }

    @Test
    void backfill_ignores_todoread_and_malformed_and_session_without_history() {
        String sid = newSession("s3");
        // todoread 是读操作不产生快照；参数不可解析的 todowrite 也不落。
        assistantMessage(sid, new ToolCall("todoread", "[{\"content\":\"x\",\"status\":\"pending\"}]", null));
        assistantMessage(sid, new ToolCall("todowrite", "not-json", null));
        assertTrue(repo.findTodos(sid).isEmpty(), "todoread/malformed must not produce a snapshot");

        String fresh = newSession("s4");
        assertTrue(repo.findTodos(fresh).isEmpty(), "session without history stays rowless");
    }

    @Test
    void deleting_session_cascades_todo_row() {
        String sid = newSession("s5");
        repo.upsertTodos(sid, "[{\"content\":\"a\",\"status\":\"pending\"}]");
        assertTrue(repo.findTodos(sid).isPresent());
        // FK ON DELETE CASCADE（PRAGMA foreign_keys=ON 由 SqliteDataSourceFactory 强制）。
        repo.deleteMessages(sid);
        repo.delete(sid);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_todo WHERE session_id = ?", Integer.class, sid));
    }
}
