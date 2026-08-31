package gate.application.ticket;

import java.util.List;

/**
 * Creation request for a ticket. Every field except {@code title} is optional; blank strings are
 * normalized to null by the handler so web JSON and MCP arguments share one shape.
 *
 * @param ticketNo     explicit ticket number, or null for auto-generation (next T-nnn)
 * @param title        short title (required by callers, never null after normalization)
 * @param projectId    project to bind and clone from, or null for the gate-level default topology
 * @param targetBranch ticket branch name, or null to use the ticket number
 * @param stage        "PENDING" or "IN_PROGRESS" (creation only), or null for IN_PROGRESS
 * @param priority     one of {@code Ticket.PRIORITIES}, or null
 * @param description  free text, or null
 * @param note         free text, or null
 * @param labels       label list, or null for none
 * @param agentConfigId executor agent-config binding, or null (web-only concern)
 */
public record CreateTicketCommand(
        String ticketNo,
        String title,
        String projectId,
        String targetBranch,
        String stage,
        String priority,
        String description,
        String note,
        List<String> labels,
        String agentConfigId) {
}
