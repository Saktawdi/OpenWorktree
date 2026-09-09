package gate.adapters.store;

import gate.application.util.MiniJson;
import gate.domain.blob.BlobRef;
import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.domain.session.ToolCall;
import gate.domain.session.TurnPart;
import gate.ports.store.BlobStore;
import gate.ports.store.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate + BlobStore-backed {@link SessionRepository}.
 *
 * <p>clone_path 列遵循与 {@link JdbcTicketRepository} 相同的克隆根约定：注入 {@code clonesRoot}
 * 时克隆根内的绝对路径压成相对路径存储、读取时按当前克隆根解析（克隆根可随布局整体
 * 搬家而无需改行内数据）；克隆根之外的绝对路径（快速模式用户工作区等）原样存取。
 */
public final class JdbcSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;
    private final Path clonesRoot;
    private final RowMapper<Session> sessionMapper;

    public JdbcSessionRepository(JdbcTemplate jdbc, BlobStore blobs) {
        this(jdbc, blobs, null);
    }

    public JdbcSessionRepository(JdbcTemplate jdbc, BlobStore blobs, Path clonesRoot) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.clonesRoot = clonesRoot == null ? null : clonesRoot.toAbsolutePath().normalize();
        this.sessionMapper = (ResultSet rs, int n) -> new Session(
                rs.getString("id"),
                rs.getString("ticket_no"),
                rs.getString("agent_config_id"),
                AgentCli.valueOf(rs.getString("cli")),
                SessionStatus.valueOf(rs.getString("status")),
                rs.getString("cli_session_id"),
                loadPath(rs.getString("clone_path")),
                rs.getInt("allocated_port"),
                Instant.parse(rs.getString("started_at")),
                rs.getString("finished_at") == null ? null : Instant.parse(rs.getString("finished_at")),
                new SessionUsage(
                        rs.getObject("prompt_tokens") == null ? null : rs.getLong("prompt_tokens"),
                        rs.getObject("completion_tokens") == null ? null : rs.getLong("completion_tokens"),
                        rs.getObject("total_tokens") == null ? null : rs.getLong("total_tokens")),
                rs.getString("title"),
                rs.getInt("archived") != 0,
                nullableColumn(rs, "override_provider"),
                nullableColumn(rs, "override_model"),
                nullableColumn(rs, "override_variant"),
                rs.getInt("permission_auto_accept") != 0,
                nullableColumn(rs, "permission_mode"));
    }

    /** 写入端：克隆根内的绝对路径 → 相对路径；其它原样。 */
    private String storePath(String value) {
        if (value == null || clonesRoot == null) {
            return value;
        }
        Path p = Path.of(value).toAbsolutePath().normalize();
        if (p.startsWith(clonesRoot)) {
            Path rel = clonesRoot.relativize(p);
            if (!rel.toString().isEmpty()) {
                return rel.toString().replace('\\', '/');
            }
        }
        return value;
    }

    /** 读取端：相对路径按当前克隆根解析回绝对路径。 */
    private String loadPath(String value) {
        if (value == null || clonesRoot == null) {
            return value;
        }
        Path p = Path.of(value);
        if (p.isAbsolute()) {
            return value;
        }
        return clonesRoot.resolve(value.replace('/', java.io.File.separatorChar)).toString();
    }

    private static String nullableColumn(ResultSet rs, String column) throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public Optional<Session> find(String id) {
        List<Session> rows = jdbc.query("SELECT * FROM agent_session WHERE id = ?", sessionMapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Session> findByTicket(String ticketNo) {
        return jdbc.query("SELECT * FROM agent_session WHERE ticket_no = ? ORDER BY started_at", sessionMapper, ticketNo);
    }

    @Override
    public List<Session> findByAgentConfig(String agentConfigId) {
        return jdbc.query("SELECT * FROM agent_session WHERE agent_config_id = ? ORDER BY started_at",
                sessionMapper, agentConfigId);
    }

    @Override
    public List<Session> findByStatus(SessionStatus status) {
        return jdbc.query("SELECT * FROM agent_session WHERE status = ? ORDER BY started_at",
                sessionMapper, status.name());
    }

    @Override
    public void insert(Session session) {
        SessionUsage u = session.cumulativeUsage() == null ? SessionUsage.EMPTY : session.cumulativeUsage();
        jdbc.update("""
                INSERT INTO agent_session(id, ticket_no, agent_config_id, cli, status, cli_session_id,
                                          clone_path, allocated_port, prompt_tokens, completion_tokens,
                                          total_tokens, started_at, finished_at, title, archived,
                                          override_provider, override_model, override_variant,
                                          permission_auto_accept, permission_mode)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                session.id(), session.ticketNo(), session.agentConfigId(), session.cli().name(),
                session.status().name(), session.cliSessionId(), storePath(session.clonePath()),
                session.allocatedPort(), u.promptTokens(), u.completionTokens(), u.totalTokens(),
                session.startedAt().toString(),
                session.finishedAt() == null ? null : session.finishedAt().toString(),
                session.title(),
                session.archived() ? 1 : 0,
                session.overrideProvider(), session.overrideModel(), session.overrideVariant(),
                session.permissionAutoAccept() ? 1 : 0,
                session.permissionMode());
    }

    @Override
    public void update(Session session) {
        SessionUsage u = session.cumulativeUsage() == null ? SessionUsage.EMPTY : session.cumulativeUsage();
        jdbc.update("""
                UPDATE agent_session SET status = ?, cli_session_id = ?, allocated_port = ?,
                       prompt_tokens = ?, completion_tokens = ?, total_tokens = ?, finished_at = ?,
                       title = ?, archived = ?, override_provider = ?, override_model = ?,
                       override_variant = ?, permission_auto_accept = ?, permission_mode = ?
                WHERE id = ?
                """,
                session.status().name(), session.cliSessionId(), session.allocatedPort(),
                u.promptTokens(), u.completionTokens(), u.totalTokens(),
                session.finishedAt() == null ? null : session.finishedAt().toString(),
                session.title(),
                session.archived() ? 1 : 0,
                session.overrideProvider(), session.overrideModel(), session.overrideVariant(),
                session.permissionAutoAccept() ? 1 : 0,
                session.permissionMode(),
                session.id());
    }

    @Override
    public void insertMessage(SessionMessage message) {
        byte[] contentBytes = message.content() == null ? new byte[0] : message.content().getBytes(StandardCharsets.UTF_8);
        BlobRef ref = blobs.put(contentBytes, "session/" + message.sessionId() + "/" + message.id() + ".txt");
        String toolJson = writeToolCalls(message.toolCalls());
        String partsJson = writeParts(message.parts());
        SessionUsage u = message.usage();
        jdbc.update("""
                INSERT INTO session_message(id, session_id, role, content_blob, content_bytes,
                                            tool_calls_blob, parts_blob, prompt_tokens,
                                            completion_tokens, total_tokens, degraded, created_at,
                                            model_provider, model_id, reasoning_variant)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                message.id(), message.sessionId(), message.role().name(), ref.relPath(), ref.bytes(),
                toolJson, partsJson, u == null ? null : u.promptTokens(), u == null ? null : u.completionTokens(),
                u == null ? null : u.totalTokens(), message.degraded() ? 1 : 0,
                message.timestamp().toString(),
                message.modelProvider(), message.modelId(), message.reasoningVariant());
    }

    @Override
    public List<SessionMessage> findMessages(String sessionId) {
        // rowid 破平：claude headless 一回合多条消息共享同一 created_at（回合开始取一次
        // 时间），按 id（随机 UUID）破平等于随机洗牌——历史视图里用户消息会排到触发它
        // 的 agent 回复之后。rowid = 插入序 = 真实时序，两代 CLI 都严格更优。
        return jdbc.query("""
                SELECT * FROM session_message WHERE session_id = ? ORDER BY created_at, rowid
                """, (ResultSet rs, int n) -> {
            String id = rs.getString("id");
            String blobPath = rs.getString("content_blob");
            int contentBytes = rs.getInt("content_bytes");
            BlobRef ref = new BlobRef(blobPath, contentBytes, "0".repeat(64));
            String content = new String(blobs.get(ref), StandardCharsets.UTF_8);
            Long prompt = rs.getObject("prompt_tokens") == null ? null : rs.getLong("prompt_tokens");
            Long completion = rs.getObject("completion_tokens") == null ? null : rs.getLong("completion_tokens");
            Long total = rs.getObject("total_tokens") == null ? null : rs.getLong("total_tokens");
            SessionUsage usage = prompt == null && completion == null && total == null
                    ? null : new SessionUsage(prompt, completion, total);
            // V20 之前落库的行没有该列（SELECT * 拿不到），按空时间线回退两段式渲染。
            List<TurnPart> parts;
            try {
                parts = parseParts(rs.getString("parts_blob"));
            } catch (Exception columnAbsent) {
                parts = List.of();
            }
            // V22 之前落库的行没有模型列：同样按列缺失容错，读取端回退近似标注。
            String modelProvider = null;
            String modelId = null;
            String reasoningVariant = null;
            try {
                modelProvider = rs.getString("model_provider");
                modelId = rs.getString("model_id");
                reasoningVariant = rs.getString("reasoning_variant");
            } catch (Exception columnAbsent) {
                // pre-V22 row
            }
            return new SessionMessage(
                    id,
                    sessionId,
                    Role.valueOf(rs.getString("role")),
                    content,
                    parseToolCalls(rs.getString("tool_calls_blob")),
                    usage,
                    rs.getInt("degraded") != 0,
                    Instant.parse(rs.getString("created_at")),
                    parts,
                    modelProvider,
                    modelId,
                    reasoningVariant);
        }, sessionId);
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM agent_session WHERE id = ?", id);
    }

    @Override
    public void deleteMessages(String sessionId) {
        jdbc.update("DELETE FROM session_message WHERE session_id = ?", sessionId);
    }

    // -------------------------------------------------------------------------------------------
    // V21 session_todo：每会话一份任务清单快照（行存在 = 有清单，"[]" = 显式清空）。
    // 删除会话由 FK ON DELETE CASCADE 清理，无需显式 delete 方法。
    // -------------------------------------------------------------------------------------------

    @Override
    public void upsertTodos(String sessionId, String todosJson) {
        if (todosJson == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO session_todo(session_id, todos_json, updated_at) VALUES (?,?,?)
                ON CONFLICT(session_id) DO UPDATE SET todos_json = excluded.todos_json,
                                                      updated_at = excluded.updated_at
                """, sessionId, todosJson, clockNow());
    }

    @Override
    public Optional<String> findTodos(String sessionId) {
        List<String> rows = jdbc.query("SELECT todos_json FROM session_todo WHERE session_id = ?",
                (rs, n) -> rs.getString(1), sessionId);
        if (!rows.isEmpty()) {
            return Optional.of(rows.get(0));
        }
        // Lazy backfill：V21 之前的历史会话没有快照行，扫一次消息历史取最后一条合法
        // todowrite 落行（tool_calls_blob 的 arguments_json 是内嵌 JSON 字符串，双层解析）。
        // 一次成本，之后永久走快照；新会话（无历史）不落行、返回 empty。
        List<String> blobs = jdbc.query("""
                SELECT tool_calls_blob FROM session_message
                WHERE session_id = ? AND tool_calls_blob IS NOT NULL
                ORDER BY created_at DESC, rowid DESC
                """, (rs, n) -> rs.getString(1), sessionId);
        for (String blob : blobs) {
            for (ToolCall tc : parseToolCalls(blob)) {
                String canonical = gate.adapters.session.TodoSnapshots.canonicalJson(tc.argumentsJson());
                if (canonical != null && gate.adapters.session.TodoSnapshots.isWriteTool(tc.name())) {
                    upsertTodos(sessionId, canonical);
                    return Optional.of(canonical);
                }
            }
        }
        return Optional.empty();
    }

    private static String clockNow() {
        return java.time.Instant.now().toString();
    }

    // -------------------------------------------------------------------------------------------
    // V24 session_task：claude 任务清单 journal（TaskCreate/TaskUpdate 事件累积，非全量
    // 快照）。删除会话由 FK ON DELETE CASCADE 清理，无需显式 delete 方法。
    // -------------------------------------------------------------------------------------------

    @Override
    public void upsertTasks(String sessionId, String tasksJson) {
        if (tasksJson == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO session_task(session_id, tasks_json, updated_at) VALUES (?,?,?)
                ON CONFLICT(session_id) DO UPDATE SET tasks_json = excluded.tasks_json,
                                                      updated_at = excluded.updated_at
                """, sessionId, tasksJson, clockNow());
    }

    @Override
    public Optional<String> findTasks(String sessionId) {
        List<String> rows = jdbc.query("SELECT tasks_json FROM session_task WHERE session_id = ?",
                (rs, n) -> rs.getString(1), sessionId);
        if (!rows.isEmpty()) {
            return Optional.of(rows.get(0));
        }
        // 非 claude 会话直接短路：TaskCreate/TaskUpdate 是 claude 独有工具名，opencode
        // 永远不会产生任务事件——不做 lazy 回填扫描（/messages 附带查询是共享路径，
        // 不能为每个 opencode 会话付一次全量 parts 解析）。
        List<String> cli = jdbc.query("SELECT cli FROM agent_session WHERE id = ?",
                (rs, n) -> rs.getString(1), sessionId);
        if (cli.isEmpty() || !"CLAUDE".equals(cli.get(0))) {
            return Optional.empty();
        }
        // Lazy backfill：V24 之前的历史 claude 会话没有 journal 行，扫一次消息 parts 全量
        // 重放 TaskCreate/TaskUpdate（确定性事件源）落行后返回。与 todos 的回填同哲学：
        // 一次成本，之后永久走 journal；无任务工具历史的会话不落行、返回 empty。
        List<gate.domain.session.TurnPart> events = findToolParts(sessionId);
        String replayed = gate.adapters.session.ClaudeTaskSnapshots.replayJson(events);
        if (replayed == null) {
            return Optional.empty();
        }
        upsertTasks(sessionId, replayed);
        return Optional.of(replayed);
    }

    @Override
    public List<TurnPart> findToolParts(String sessionId) {
        // rowid = 插入序 = 真实时序（与 findMessages 同口径破平）。
        List<String> blobs = jdbc.query("""
                SELECT parts_blob FROM session_message
                WHERE session_id = ? AND parts_blob IS NOT NULL
                ORDER BY rowid
                """, (rs, n) -> rs.getString(1), sessionId);
        List<TurnPart> out = new ArrayList<>();
        for (String blob : blobs) {
            for (TurnPart p : parseParts(blob)) {
                if (p.isTool()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------------------------
    // Small JSON helpers (no new dependency).
    // -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (parsed instanceof List<?> list) {
                List<ToolCall> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> mm = (Map<String, Object>) m;
                        out.add(new ToolCall(
                                String.valueOf(mm.get("name")),
                                String.valueOf(mm.getOrDefault("arguments_json", "")),
                                mm.get("result_json") == null ? null : String.valueOf(mm.get("result_json"))));
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // malformed tool calls are not fatal
        }
        return List.of();
    }

    private static String writeToolCalls(List<ToolCall> calls) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (calls != null) {
            for (ToolCall c : calls) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("arguments_json", c.argumentsJson());
                m.put("result_json", c.resultJson());
                out.add(m);
            }
        }
        return write(out);
    }

    @SuppressWarnings("unchecked")
    private static List<TurnPart> parseParts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (parsed instanceof List<?> list) {
                List<TurnPart> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> mm = (Map<String, Object>) m;
                        String type = String.valueOf(mm.get("type"));
                        if ("tool".equals(type)) {
                            out.add(TurnPart.tool(
                                    String.valueOf(mm.get("name")),
                                    String.valueOf(mm.getOrDefault("arguments_json", "")),
                                    mm.get("result_json") == null ? null : String.valueOf(mm.get("result_json"))));
                        } else if ("thinking".equals(type)) {
                            out.add(TurnPart.thinking(String.valueOf(mm.getOrDefault("text", ""))));
                        } else if ("steer".equals(type)) {
                            // T-107 渲染修复：name 承载 USER 行 id（历史重建按其去重被吞并行）。
                            out.add(TurnPart.steer(String.valueOf(mm.getOrDefault("name", "")),
                                    String.valueOf(mm.getOrDefault("text", ""))));
                        } else {
                            // 未知类型按文本容错，保住其余分段的顺序
                            out.add(TurnPart.text(String.valueOf(mm.getOrDefault("text", ""))));
                        }
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // malformed parts are not fatal — the row still has the flat view
        }
        return List.of();
    }

    private static String writeParts(List<TurnPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TurnPart p : parts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.type());
            if (p.isTool()) {
                m.put("name", p.name());
                m.put("arguments_json", p.argumentsJson());
                m.put("result_json", p.resultJson());
            } else if (p.isSteer()) {
                m.put("name", p.name());
                m.put("text", p.text());
            } else {
                m.put("text", p.text());
            }
            out.add(m);
        }
        return write(out);
    }

    private static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String raw) {
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
