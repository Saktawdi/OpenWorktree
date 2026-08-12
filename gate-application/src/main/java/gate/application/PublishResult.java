package gate.application;

/**
 * Outcome of a publish.
 *
 * @param alreadyPublished true when the intent's commit was already an ancestor of the target ref,
 *                         i.e. this call was an idempotent replay and produced no second commit
 */
public record PublishResult(
        String ticketNo,
        int reviewRound,
        String treeHash,
        String commitSha,
        String targetRef,
        String refBefore,
        String refAfter,
        boolean alreadyPublished) {
}
