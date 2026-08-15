package gate.adapters.store;

import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.TicketRepository;
import java.sql.ResultSet;
import java.time.Instant;
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
            rs.getString("exec_token_source"));

    @Override
    public void insert(Ticket ticket) {
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path,
                                   executor_provider_id, executor_model,
                                   reviewer_provider_id, reviewer_model,
                                   stage, created_at, updated_at,
                                   exec_token_total, exec_token_source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                ticket.ticketNo(), ticket.title(), ticket.targetRef(), ticket.clonePath(),
                ticket.executorProviderId(), ticket.executorModel(),
                ticket.reviewerProviderId(), ticket.reviewerModel(),
                ticket.stage().name(), ticket.createdAt().toString(), ticket.updatedAt().toString(),
                ticket.execTokenTotal(), ticket.execTokenSource());
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
    public List<Ticket> findByStage(TicketStage stage) {
        return jdbc.query("SELECT * FROM ticket WHERE stage = ? ORDER BY ticket_no", MAPPER, stage.name());
    }

    @Override
    public List<Ticket> findAll() {
        return jdbc.query("SELECT * FROM ticket ORDER BY ticket_no", MAPPER);
    }

    private static Long getNullableLong(ResultSet rs, String column) {
        try {
            long val = rs.getLong(column);
            return rs.wasNull() ? null : val;
        } catch (Exception e) {
            return null;
        }
    }
}
