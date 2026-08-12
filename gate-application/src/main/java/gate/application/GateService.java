package gate.application;

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

    StatusResult status(StatusQuery query);
}
