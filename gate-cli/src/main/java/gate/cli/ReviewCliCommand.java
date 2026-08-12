package gate.cli;

import gate.application.ReviewCommand;
import gate.application.ReviewResult;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate review}: record a human verdict for a presubmit round.
 *
 * <p>P1 has no LLM engine; the verdict is supplied here but still flows through the ordinary
 * {@code ReviewEngine} contract and is turned into a decision only by {@code GatePolicy}. A REJECT
 * exits 10 (feed findings back), matching §8.3.
 */
@CommandLine.Command(name = "review", description = "Record a manual verdict for a presubmit round")
final class ReviewCliCommand extends BaseCommand {

    @CommandLine.Option(names = "--ticket", required = true, description = "Ticket number")
    String ticketNo;

    @CommandLine.Option(names = "--round", description = "Round (default: latest)")
    Integer round;

    @CommandLine.ArgGroup(multiplicity = "1")
    Verdict verdict;

    static final class Verdict {
        @CommandLine.Option(names = "--pass", description = "Approve this round")
        boolean pass;

        @CommandLine.Option(names = "--reject", description = "Reject this round (requires --note)")
        boolean reject;
    }

    @CommandLine.Option(names = "--note", description = "Reject reason (becomes the BLOCKER finding message)")
    String note;

    @Override
    public void run() {
        GateComponents c = components();
        boolean pass = verdict.pass;
        ReviewResult r = c.gateService().review(new ReviewCommand(ticketNo, round, pass, note));
        JsonOut.emit(System.out, "review", Map.of(
                "ticket_no", r.ticketNo(),
                "review_round", r.reviewRound(),
                "tree_hash", r.treeHash(),
                "dangling_commit", r.danglingCommit(),
                "verdict", r.verdict().name(),
                "reason", r.reason(),
                "detail", r.detail()));
        switch (r.verdict()) {
            case PASS -> { /* exit 0 */ }
            case REJECT -> throw new GateException(GateErrorCode.REJECT_FINDINGS, r.reason());
            case REQUIRES_HUMAN -> throw new GateException(GateErrorCode.REJECT_NEEDS_HUMAN, r.reason());
        }
    }
}
