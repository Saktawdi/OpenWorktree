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

    List<Ticket> findByStage(TicketStage stage);

    List<Ticket> findAll();
}
