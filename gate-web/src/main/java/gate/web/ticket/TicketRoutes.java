package gate.web.ticket;

import gate.application.GateService;
import gate.ports.TicketRepository;

/**
 * Ticket capability handler (EX-001 Phase 1).
 * Owns /api/tickets and /api/projects/{id}/tickets routes.
 * Extracted from ApiRoutes (1538 lines) to satisfy GOV-CPLX-001 and capability-registry.
 * Current status: skeleton for L3 implementation, L4 approval required for full cutover (EX-001).
 * Delegates to GateService/ticket repository via ports; no business logic duplication.
 */
public final class TicketRoutes {
    private final GateService gateService;
    private final TicketRepository tickets;

    public TicketRoutes(GateService gateService, TicketRepository tickets) {
        this.gateService = gateService;
        this.tickets = tickets;
    }

    // Future: move ticketList/ticketCreate/ticketUpdate/ticketDetail here.
    // Keeping ApiRoutes as facade until EX-001 is Approved and delta_lte_zero is verified.
}
