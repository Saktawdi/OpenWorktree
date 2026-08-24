package gate.application.ticket;

import gate.domain.ticket.Ticket;
import gate.ports.store.TicketRepository;
import java.time.Instant;

/**
 * Ticket capability service (capability-registry: ticket).
 * Will own ticket lifecycle, stage transitions via TicketTransitionPort.
 * L2 implement, L3 review. review/publish must not directly write stage.
 */
public final class TicketService {
    private final TicketRepository tickets;

    public TicketService(TicketRepository tickets) {
        this.tickets = tickets;
    }

    public Ticket find(String ticketNo) {
        return tickets.find(ticketNo).orElse(null);
    }
}
