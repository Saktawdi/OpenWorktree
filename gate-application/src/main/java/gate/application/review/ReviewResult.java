package gate.application.review;

import gate.domain.policy.Decision;
import java.util.List;

/** Outcome of a review round. */
public record ReviewResult(
        String ticketNo,
        int reviewRound,
        String treeHash,
        String danglingCommit,
        Decision.Verdict verdict,
        String reason,
        List<String> detail) {

    public ReviewResult {
        detail = List.copyOf(detail);
    }
}
