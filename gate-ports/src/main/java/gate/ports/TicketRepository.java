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

    /** Returns only the tickets owned by one project (project-scoped board). */
    default List<Ticket> findAllByProject(String projectId) {
        return findAll().stream()
                .filter(ticket -> projectId != null && projectId.equals(ticket.projectId()))
                .toList();
    }

    /**
     * Updates the editable (non-gate-controlled) ticket fields (V5 web console). A {@code null}
     * priority explicitly clears it; {@code null} title keeps the stored title.
     */
    default void updateEditable(String ticketNo, String title, String priority, Instant now) {
        throw new UnsupportedOperationException("editable ticket update is not supported");
    }

    /**
     * Updates the editable work item content. The text values are nullable so callers can clear
     * the requirement description or note; labels are replaced as a complete list.
     *
     * <p>The four-argument overload remains for adapters compiled against the V5 metadata shape.
     */
    default void updateEditable(String ticketNo, String title, String priority,
                                String description, String note, List<String> labels, Instant now) {
        updateEditable(ticketNo, title, priority, now);
    }

    /**
     * Rebinds the agent config executing a ticket (web drawer assignment). A {@code null} id
     * detaches the ticket from any agent (back to manual handling).
     */
    default void updateAgentConfig(String ticketNo, String agentConfigId, Instant now) {
        throw new UnsupportedOperationException("agent config update is not supported");
    }

    /** Detaches every ticket from a project (project delete path, legacy compatibility). */
    default void clearProject(String projectId, Instant now) {
        throw new UnsupportedOperationException("project detach is not supported");
    }
}
