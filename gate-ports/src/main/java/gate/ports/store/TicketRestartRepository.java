package gate.ports.store;

import gate.domain.ticket.TicketStage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the {@code ticket_restart} table (T-117 重启工单).
 *
 * <p>One row per restart of a terminal (DONE/CANCELLED) ticket. {@code round} is the presubmit
 * round the restart opens ({@link PresubmitRepository#nextRound} at restart time) — presubmit
 * itself keeps allocating MAX(review_round)+1, so round numbering continues seamlessly.
 */
public interface TicketRestartRepository {

    record RestartRow(String ticketNo, int round, TicketStage fromStage, String reason, Instant createdAt) {
    }

    void insert(RestartRow row);

    /** Full history for one ticket, oldest first. */
    List<RestartRow> findByTicket(String ticketNo);

    /** The most recent restart, if any — feeds the 系统注入词 重启理由 field. */
    default Optional<RestartRow> latest(String ticketNo) {
        List<RestartRow> rows = findByTicket(ticketNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(rows.size() - 1));
    }

    default long count(String ticketNo) {
        return findByTicket(ticketNo).size();
    }
}
