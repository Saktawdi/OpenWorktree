package gate.ports;

import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@code ticket} table. */
public interface TicketRepository {

    void insert(Ticket ticket);

    Optional<Ticket> find(String ticketNo);

    void updateStage(String ticketNo, TicketStage stage, Instant now);

    /** Writes cumulative agent-cli token usage back to a ticket (执行文档-后端-web §5.7). */
    void updateExecTokens(String ticketNo, long totalTokens, String source, Instant now);

    List<Ticket> findByStage(TicketStage stage);

    List<Ticket> findAll();
}
