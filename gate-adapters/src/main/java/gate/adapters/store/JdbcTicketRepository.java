package gate.adapters.store;

import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.store.TicketRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JdbcTemplate-backed {@code ticket} store (no JPA — §4.4). */
public final class JdbcTicketRepository implements TicketRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Ticket> MAPPER = (ResultSet rs, int n) -> new Ticket(
            rs.getString("ticket_no"),
            rs.getString("title"),
            rs.getString("target_ref"),
            rs.getString("clone_path"),
            rs.getString("executor_provider_id"),
            rs.getString("executor_model"),
            rs.getString("reviewer_provider_id"),
            rs.getString("reviewer_model"),
            TicketStage.valueOf(rs.getString("stage")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            getNullableLong(rs, "exec_token_total"),
            rs.getString("exec_token_source"),
            rs.getString("agent_config_id"),
            rs.getString("priority"),
            rs.getString("project_id"),
            rs.getString("description"),
            rs.getString("note"),
            decodeLabels(rs.getString("labels")));

    @Override
    public void insert(Ticket ticket) {
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path,
                                   executor_provider_id, executor_model,
                                   reviewer_provider_id, reviewer_model,
                                   stage, created_at, updated_at,
                                   exec_token_total, exec_token_source,
                                   agent_config_id, priority, project_id,
                                   description, note, labels)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                ticket.ticketNo(), ticket.title(), ticket.targetRef(), ticket.clonePath(),
                ticket.executorProviderId(), ticket.executorModel(),
                ticket.reviewerProviderId(), ticket.reviewerModel(),
                ticket.stage().name(), ticket.createdAt().toString(), ticket.updatedAt().toString(),
                ticket.execTokenTotal(), ticket.execTokenSource(),
                ticket.agentConfigId(), ticket.priority(), ticket.projectId(),
                ticket.description(), ticket.note(), encodeLabels(ticket.labels()));
    }

    @Override
    public Optional<Ticket> find(String ticketNo) {
        List<Ticket> rows = jdbc.query("SELECT * FROM ticket WHERE ticket_no = ?", MAPPER, ticketNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void updateStage(String ticketNo, TicketStage stage, Instant now) {
        int updated = jdbc.update("UPDATE ticket SET stage = ?, updated_at = ? WHERE ticket_no = ?",
                stage.name(), now.toString(), ticketNo);
        if (updated != 1) {
            throw new IllegalStateException("no such ticket: " + ticketNo);
        }
    }

    @Override
    public void updateExecTokens(String ticketNo, long totalTokens, String source, Instant now) {
        int updated = jdbc.update("""
                UPDATE ticket SET exec_token_total = ?, exec_token_source = ?, updated_at = ?
                WHERE ticket_no = ?
                """, totalTokens, source, now.toString(), ticketNo);
        if (updated != 1) {
            throw new IllegalStateException("no such ticket: " + ticketNo);
        }
    }

    @Override
    public void updateEditable(String ticketNo, String title, String priority, Instant now) {
        // COALESCE keeps the stored title when the caller passes null, while a null priority is an
        // explicit "clear" (the web PATCH semantics, V5).
        int updated = jdbc.update(
                "UPDATE ticket SET title = COALESCE(?, title), priority = ?, updated_at = ? WHERE ticket_no = ?",
                title, priority, now.toString(), ticketNo);
        if (updated != 1) {
            throw new IllegalStateException("no such ticket: " + ticketNo);
        }
    }

    @Override
    public void updateEditable(String ticketNo, String title, String priority,
                               String description, String note, List<String> labels, Instant now) {
        int updated = jdbc.update("""
                UPDATE ticket SET title = COALESCE(?, title), priority = ?, description = ?,
                                   note = ?, labels = ?, updated_at = ?
                WHERE ticket_no = ?
                """, title, priority, description, note, encodeLabels(labels), now.toString(), ticketNo);
        if (updated != 1) {
            throw new IllegalStateException("no such ticket: " + ticketNo);
        }
    }

    @Override
    public void updateAgentConfig(String ticketNo, String agentConfigId, Instant now) {
        int updated = jdbc.update(
                "UPDATE ticket SET agent_config_id = ?, updated_at = ? WHERE ticket_no = ?",
                agentConfigId, now.toString(), ticketNo);
        if (updated != 1) {
            throw new IllegalStateException("no such ticket: " + ticketNo);
        }
    }

    @Override
    public void clearProject(String projectId, Instant now) {
        jdbc.update("UPDATE ticket SET project_id = NULL, updated_at = ? WHERE project_id = ?",
                now.toString(), projectId);
    }

    @Override
    public List<Ticket> findByStage(TicketStage stage) {
        return jdbc.query("SELECT * FROM ticket WHERE stage = ? ORDER BY ticket_no", MAPPER, stage.name());
    }

    @Override
    public List<Ticket> findAll() {
        return jdbc.query("SELECT * FROM ticket ORDER BY ticket_no", MAPPER);
    }

    @Override
    public List<Ticket> findAllByProject(String projectId) {
        return jdbc.query("SELECT * FROM ticket WHERE project_id = ? ORDER BY ticket_no", MAPPER,
                projectId);
    }

    private static Long getNullableLong(ResultSet rs, String column) {
        try {
            long val = rs.getLong(column);
            return rs.wasNull() ? null : val;
        } catch (Exception e) {
            return null;
        }
    }

    // --- labels column codec (JSON string array; no JSON library on this module's classpath) ---

    private static String encodeLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeLabel(labels.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escapeLabel(String value) {
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

    private static List<String> decodeLabels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            int i = 0;
            int n = raw.length();
            if (raw.charAt(i) != '[') {
                throw new IllegalArgumentException("expected '['");
            }
            i++;
            while (i < n && raw.charAt(i) != ']') {
                if (raw.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (raw.charAt(i) != '"') {
                    throw new IllegalArgumentException("expected a quoted label");
                }
                StringBuilder label = new StringBuilder();
                i++;
                while (i < n && raw.charAt(i) != '"') {
                    char c = raw.charAt(i);
                    if (c == '\\') {
                        if (++i >= n) {
                            throw new IllegalArgumentException("dangling escape");
                        }
                        char escaped = raw.charAt(i);
                        switch (escaped) {
                            case 'n' -> label.append('\n');
                            case 'r' -> label.append('\r');
                            case 't' -> label.append('\t');
                            case 'b' -> label.append('\b');
                            case 'f' -> label.append('\f');
                            case 'u' -> {
                                if (i + 4 >= n) {
                                    throw new IllegalArgumentException("truncated unicode escape");
                                }
                                label.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                            default -> label.append(escaped);
                        }
                    } else {
                        label.append(c);
                    }
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("unterminated label");
                }
                i++;
                out.add(label.toString());
            }
            if (i >= n || raw.charAt(i) != ']' || i + 1 != n) {
                throw new IllegalArgumentException("expected closing bracket");
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
