package gate.application;
import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.application.status.ReconcileCommand;
import gate.application.presubmit.PresubmitCommand;
import gate.application.presubmit.PresubmitResult;
import gate.application.status.StatusResult;
import gate.application.status.ReconcileResult;
import gate.application.publish.PublishResult;
import gate.application.status.StatusQuery;
import gate.application.publish.PublishCommand;


/**
 * The single application service. CLI (P1) and MCP (P3) are both driver adapters over it; there is
 * deliberately no {@code GateTransport} port (§5.4).
 *
 * <p>No method returns a formatted string and none knows an exit code — formatting and exit codes
 * live only in the CLI adapter.
 */
public interface GateService {

    PresubmitResult presubmit(PresubmitCommand command);

    ReviewResult review(ReviewCommand command);

    PublishResult publish(PublishCommand command);

    ReconcileResult reconcile(ReconcileCommand command);

    gate.ports.git.CloneBaseSyncer.Report syncBase(gate.application.basesync.SyncBaseCommand command);

    /**
     * Creates a ticket (validates, cuts the branch, materializes the clone). Serves both the web
     * console and the agent-facing MCP {@code ticket_create} tool from one rule set.
     */
    gate.domain.ticket.Ticket createTicket(gate.application.ticket.CreateTicketCommand command);

    StatusResult status(StatusQuery query);
}
