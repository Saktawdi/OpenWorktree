package gate.application.status;

import java.util.List;

/**
 * Outcome of a reconcile pass.
 *
 * <p>Convergence is derived from {@code auth.git}, never from the DB (I4): each pending intent is
 * resolved by asking whether its {@code commit_sha} is an ancestor of the target ref.
 */
public record ReconcileResult(List<IntentOutcome> outcomes) {

    public ReconcileResult {
        outcomes = List.copyOf(outcomes);
    }

    public record IntentOutcome(
            long intentId,
            String ticketNo,
            int reviewRound,
            String treeHash,
            String commitSha,
            String from,
            String to,
            String reason) {
    }
}
