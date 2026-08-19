package gate.application.presubmit;

import gate.application.PresubmitCommand;
import gate.application.PresubmitResult;
import gate.domain.ticket.Ticket;
import gate.ports.TicketRepository;

/**
 * Presubmit capability handler (EX-002).
 * Will own presubmit use case extracted from GateServiceImpl (663 lines).
 * Capability-registry: presubmit, owner presubmit, implements TOCTOU invariant.
 * L3 implement, L4 approve boundary (EX-002, DEBT-002).
 */
public final class PresubmitHandler {
    private final TicketRepository tickets;

    public PresubmitHandler(TicketRepository tickets) {
        this.tickets = tickets;
    }

    public PresubmitResult handle(PresubmitCommand cmd) {
        // Delegates to GateServiceImpl until full cutover; skeleton preserves capability boundary.
        throw new UnsupportedOperationException("PresubmitHandler not yet wired - see EX-002");
    }
}
