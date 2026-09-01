package gate.adapters.store;

import gate.domain.project.Project;
import gate.ports.store.ProjectRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JdbcTemplate-backed {@code project} registry store (V5 web console). */
public final class JdbcProjectRepository implements ProjectRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcProjectRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Project> MAPPER = (rs, n) -> new Project(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("workspace_path"),
            rs.getString("target_ref"),
            rs.getString("auth_repo"),
            rs.getString("priority"),
            rs.getString("size"),
            decodeTags(rs.getString("tags")),
            rs.getBoolean("starred"),
            rs.getLong("sort_order"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")));

    @Override
    public void insert(Project project) {
        jdbc.update("""
                INSERT INTO project(id, name, workspace_path, target_ref, auth_repo, priority, size, tags,
                                    starred, sort_order, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                project.id(), project.name(), project.workspacePath(), project.targetRef(),
                project.authRepo(), project.priority(), project.size(), encodeTags(project.tags()),
                project.starred() ? 1 : 0, project.sortOrder(),
                project.createdAt().toString(), project.updatedAt().toString());
    }

    @Override
    public Optional<Project> find(String id) {
        List<Project> rows = jdbc.query("SELECT * FROM project WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<String> findIdByWorkspacePath(String workspacePath) {
        List<String> rows = jdbc.query(
                "SELECT id FROM project WHERE workspace_path = ?",
                (rs, n) -> rs.getString("id"), workspacePath);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Project> findAll() {
        // 首页看板顺序：星标置顶 → 手动拖拽顺序（0 = 未排，收尾兜底按名称）
        return jdbc.query(
                "SELECT * FROM project ORDER BY starred DESC, sort_order ASC, name ASC", MAPPER);
    }

    @Override
    public void update(Project project) {
        int updated = jdbc.update("""
                UPDATE project SET name = ?, workspace_path = ?, target_ref = ?, auth_repo = ?,
                                   priority = ?, size = ?, tags = ?, starred = ?, sort_order = ?,
                                   updated_at = ?
                WHERE id = ?
                """,
                project.name(), project.workspacePath(), project.targetRef(), project.authRepo(),
                project.priority(), project.size(), encodeTags(project.tags()),
                project.starred() ? 1 : 0, project.sortOrder(),
                project.updatedAt().toString(), project.id());
        if (updated != 1) {
            throw new IllegalStateException("no such project: " + project.id());
        }
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM project WHERE id = ?", id);
    }

    // --- tags column codec (JSON string array; no JSON library on this module's classpath) ---------

    /** Encodes {@code ["a","b"]}; every character we write is read back by {@link #decodeTags}. */
    private static String encodeTags(List<String> tags) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeTag(tags.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escapeTag(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
        return sb.toString();
    }

    /**
     * Strict decoder for exactly what {@link #encodeTags} writes. A hand-tampered cell degrades to
     * an empty list (with a warning) rather than failing the whole project listing.
     */
    private static List<String> decodeTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            int i = 0;
            final int n = raw.length();
            if (raw.charAt(i) != '[') {
                throw new IllegalArgumentException("expected '['");
            }
            i++;
            while (i < n && raw.charAt(i) != ']') {
                char c = raw.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c != '"') {
                    throw new IllegalArgumentException("expected '\"' or ',' or ']'");
                }
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && raw.charAt(i) != '"') {
                    char ch = raw.charAt(i);
                    if (ch == '\\') {
                        if (++i >= n) {
                            throw new IllegalArgumentException("dangling escape");
                        }
                        char next = raw.charAt(i);
                        switch (next) {
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                if (i + 4 >= n) {
                                    throw new IllegalArgumentException("truncated \\u escape");
                                }
                                sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                            default -> sb.append(next); // \" and \\ (and any passthrough)
                        }
                    } else {
                        sb.append(ch);
                    }
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("unterminated string");
                }
                i++; // closing quote
                out.add(sb.toString());
            }
            if (i >= n || raw.charAt(i) != ']' || i + 1 != n) {
                throw new IllegalArgumentException("expected ']' at end");
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            LOG.warn("corrupt tags column, treating as empty: {} ({})", raw, e.getMessage());
            return List.of();
        }
    }
}
