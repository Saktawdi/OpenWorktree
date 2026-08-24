package gate.application.status;

import java.util.List;

/** Read-only projection for {@code gate status}. */
public record StatusResult(String targetRef, String authTip, long authCommitCount, List<TicketStatus> tickets) {

    public StatusResult {
        tickets = List.copyOf(tickets);
    }

    public record TicketStatus(
            String ticketNo,
            String stage,
            Integer latestRound,
            String latestTreeHash,
            String latestIntentStatus,
            String latestCommitSha,
            boolean publishedInAuth) {
    }
}
