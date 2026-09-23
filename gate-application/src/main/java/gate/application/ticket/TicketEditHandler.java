package gate.application.ticket;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.ticket.Ticket;
import gate.ports.infra.Clock;
import gate.ports.store.TicketRepository;
import java.util.List;

/**
 * Edits a ticket's work-item metadata (V8 fields): title, priority, description, note and labels.
 *
 * <p><b>No stage path exists here on purpose.</b> The stage is gate-controlled: meeting
 * {@code presubmit → review → publish} or an explicit human restart/cancel is what moves a ticket
 * through the board. This handler writes only {@link TicketRepository#updateEditable} — there is
 * no {@code updateStage} call and no stage parameter to reach one — so the agent-facing MCP
 * {@code ticket_edit} tool that drives it can never move a ticket between lanes (the same reason
 * {@code review_run} / {@code commit_and_publish} are human-domain: an agent must not be able to
 * unilaterally advance its own work item).
 *
 * <p>The handler also refuses the fields the agent must not set at all: {@code project_id} and
 * {@code target_ref} are fixed at creation, and {@code agent_config_id} decides which CLI a
 * session spawns (a web-only binding). Those are enforced as unknown fields by
 * {@link TicketRequestParser#parseEdit}; this handler would simply have nowhere to put them.
 *
 * <p>Only the fields the caller actually provided are written — an omitted field keeps its stored
 * value, an empty string (or {@code []}) clears an optional one. {@link TicketRequestParser.TicketEdit}
 * carries that "provided" set, and the repository's whole-row update is fed the merged result so a
 * partial edit cannot blank the untouched columns.
 */
public final class TicketEditHandler {

    private final TicketRepository tickets;
    private final Clock clock;

    public TicketEditHandler(TicketRepository tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    public Ticket handle(TicketRequestParser.TicketEdit edit, String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "ticket_no must not be blank");
        }
        Ticket current = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        if (edit.provided().isEmpty()) {
            throw new GateException(GateErrorCode.USAGE,
                    "nothing to update: provide title, priority, description, note or labels");
        }

        String title = edit.provides("title") ? edit.title() : null;
        // Priority has three states: untouched (absent → keep), set (a value), cleared (empty
        // string → the parser normalized it to null). An absent field must keep the stored value,
        // so an absent one is fed the current value here rather than null.
        String priority = edit.provides("priority") ? edit.priority() : current.priority();
        String description = edit.provides("description") ? edit.description() : current.description();
        String note = edit.provides("note") ? edit.note() : current.note();
        List<String> labels = edit.provides("labels") ? edit.labels() : current.labels();

        tickets.updateEditable(ticketNo, title, priority, description, note, labels, clock.now());
        return tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
    }
}
