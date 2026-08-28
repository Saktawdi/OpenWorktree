package gate.adapters.store;

import gate.domain.ticket.TicketStage;
import gate.ports.store.TicketRestartRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JdbcTemplate-backed {@code ticket_restart} store (no JPA — §4.4). */
public final class JdbcTicketRestartRepository implements TicketRestartRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketRestartRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RestartRow> MAPPER = (ResultSet rs, int n) -> new RestartRow(
            rs.getString("ticket_no"),
            rs.getInt("round"),
            TicketStage.valueOf(rs.getString("from_stage")),
            rs.getString("reason"),
            Instant.parse(rs.getString("created_at")));

    @Override
    public void insert(RestartRow row) {
        jdbc.update("""
                INSERT INTO ticket_restart(ticket_no, round, from_stage, reason, created_at)
                VALUES (?,?,?,?,?)
                """,
                row.ticketNo(), row.round(), row.fromStage().name(), row.reason(),
                row.createdAt().toString());
    }

    @Override
    public List<RestartRow> findByTicket(String ticketNo) {
        return jdbc.query(
                "SELECT * FROM ticket_restart WHERE ticket_no = ? ORDER BY id ASC",
                MAPPER, ticketNo);
    }
}
