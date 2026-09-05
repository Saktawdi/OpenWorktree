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
                rs.getInt("permission_auto_accept") != 0);
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
                                          permission_auto_accept)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                session.id(), session.ticketNo(), session.agentConfigId(), session.cli().name(),
                session.status().name(), session.cliSessionId(), storePath(session.clonePath()),
                session.allocatedPort(), u.promptTokens(), u.completionTokens(), u.totalTokens(),
                session.startedAt().toString(),
                session.finishedAt() == null ? null : session.finishedAt().toString(),
                session.title(),
                session.archived() ? 1 : 0,
                session.overrideProvider(), session.overrideModel(), session.overrideVariant(),
                session.permissionAutoAccept() ? 1 : 0);
    }

    @Override
    public void update(Session session) {
        SessionUsage u = session.cumulativeUsage() == null ? SessionUsage.EMPTY : session.cumulativeUsage();
        jdbc.update("""
                UPDATE agent_session SET status = ?, cli_session_id = ?, allocated_port = ?,
                       prompt_tokens = ?, completion_tokens = ?, total_tokens = ?, finished_at = ?,
                       title = ?, archived = ?, override_provider = ?, override_model = ?,
                       override_variant = ?, permission_auto_accept = ?
                WHERE id = ?
                """,
                session.status().name(), session.cliSessionId(), session.allocatedPort(),
                u.promptTokens(), u.completionTokens(), u.totalTokens(),
                session.finishedAt() == null ? null : session.finishedAt().toString(),
                session.title(),
                session.archived() ? 1 : 0,
                session.overrideProvider(), session.overrideModel(), session.overrideVariant(),
                session.permissionAutoAccept() ? 1 : 0,
                session.id());
    }

    @Override
    public void insertMessage(SessionMessage message) {
        byte[] contentBytes = message.content() == null ? new byte[0] : message.content().getBytes(StandardCharsets.UTF_8);
        BlobRef ref = blobs.put(contentBytes, "session/" + message.sessionId() + "/" + message.id() + ".txt");
        String toolJson = writeToolCalls(message.toolCalls());
        SessionUsage u = message.usage();
        jdbc.update("""
                INSERT INTO session_message(id, session_id, role, content_blob, content_bytes,
                                            tool_calls_blob, prompt_tokens, completion_tokens,
                                            total_tokens, degraded, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                message.id(), message.sessionId(), message.role().name(), ref.relPath(), ref.bytes(),
                toolJson, u == null ? null : u.promptTokens(), u == null ? null : u.completionTokens(),
                u == null ? null : u.totalTokens(), message.degraded() ? 1 : 0,
                message.timestamp().toString());
    }

    @Override
    public List<SessionMessage> findMessages(String sessionId) {
        return jdbc.query("""
                SELECT * FROM session_message WHERE session_id = ? ORDER BY created_at, id
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
            return new SessionMessage(
                    id,
                    sessionId,
                    Role.valueOf(rs.getString("role")),
                    content,
                    parseToolCalls(rs.getString("tool_calls_blob")),
                    usage,
                    rs.getInt("degraded") != 0,
                    Instant.parse(rs.getString("created_at")));
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
