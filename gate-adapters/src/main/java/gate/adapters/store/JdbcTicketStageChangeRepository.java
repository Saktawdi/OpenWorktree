package gate.adapters.store;

import gate.domain.ticket.TicketStage;
import gate.ports.store.TicketStageChangeRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-backed {@code ticket_stage_change} store (V19; renamed from V17
 * {@code ticket_restart} with the added {@code to_stage} column).
 */
public final class JdbcTicketStageChangeRepository implements TicketStageChangeRepository {

    private final JdbcTemplate jdbc;

    public JdbcTicketStageChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<StageChangeRow> MAPPER = (ResultSet rs, int n) -> new StageChangeRow(
            rs.getString("ticket_no"),
            rs.getInt("round"),
            TicketStage.valueOf(rs.getString("from_stage")),
            toStage(rs.getString("to_stage")),
            rs.getString("reason"),
            Instant.parse(rs.getString("created_at")));

    private static TicketStage toStage(String raw) {
        return raw == null || raw.isBlank() ? null : TicketStage.valueOf(raw);
    }

    @Override
    public void insert(StageChangeRow row) {
        jdbc.update("""
                INSERT INTO ticket_stage_change(ticket_no, round, from_stage, to_stage, reason, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                row.ticketNo(), row.round(), row.fromStage().name(),
                row.toStage() == null ? null : row.toStage().name(),
                row.reason(), row.createdAt().toString());
    }

    @Override
    public List<StageChangeRow> findByTicket(String ticketNo) {
        return jdbc.query(
                "SELECT * FROM ticket_stage_change WHERE ticket_no = ? ORDER BY id ASC",
                MAPPER, ticketNo);
    }
}
