package gate.cli.cmd;
import gate.cli.BaseCommand;
import gate.cli.GateComponents;
import gate.cli.util.JsonOut;


import gate.application.review.ReviewCommand;
import gate.application.review.ReviewResult;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate review}: run a review round.
 *
 * <p>Two shapes:
 * <ul>
 *   <li>with an engine configured (P2): {@code gate review --ticket T} runs prism and GatePolicy
 *       derives the verdict from the evidence;</li>
 *   <li>without an engine (P1, or A6's blocker-branch path): {@code gate review --ticket T --pass|--reject --note ...}
 *       records a manual verdict.</li>
 * </ul>
 * A REJECT exits 10 (feed findings back), a NEEDS_HUMAN exits 13, matching §8.3.
 */
@CommandLine.Command(name = "review", description = "Run a review round for a presubmit")
public final class ReviewCliCommand extends BaseCommand {

    @CommandLine.Option(names = "--ticket", required = true, description = "Ticket number")
    String ticketNo;

    @CommandLine.Option(names = "--round", description = "Round (default: latest)")
    Integer round;

    @CommandLine.ArgGroup(multiplicity = "0..1")
    Verdict verdict;

    static final class Verdict {
        @CommandLine.Option(names = "--pass", description = "Approve this round (manual mode)")
        boolean pass;

        @CommandLine.Option(names = "--reject", description = "Reject this round (manual mode, requires --note)")
        boolean reject;
    }

    @CommandLine.Option(names = "--note", description = "Reject reason (becomes the BLOCKER finding message)")
    String note;

    @Override
    public void run() {
        GateComponents c = components();
        boolean engineMode = c.config().engineConfigured();
        ReviewCommand cmd;
        if (engineMode) {
            // gate-engine produces the evidence; no human verdict is carried.
            cmd = ReviewCommand.forEngine(ticketNo, round);
        } else {
            boolean pass = verdict != null && verdict.pass;
            boolean reject = verdict != null && verdict.reject;
            if (!pass && !reject) {
                throw new GateException(GateErrorCode.USAGE,
                        "no engine configured: provide --pass or --reject (or configure engine.kind=gate-engine in gate.toml)");
            }
            cmd = new ReviewCommand(ticketNo, round, pass, note);
        }
        ReviewResult r = c.gateService().review(cmd);
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
