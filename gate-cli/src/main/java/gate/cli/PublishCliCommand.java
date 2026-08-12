package gate.cli;

import gate.application.PublishCommand;
import gate.application.PublishResult;
import java.util.Map;
import picocli.CommandLine;

/** {@code gate publish}: commit-tree, issue approval, push through the gate (§6, §7). */
@CommandLine.Command(name = "publish", description = "Commit the reviewed tree and push it through the gate")
final class PublishCliCommand extends BaseCommand {

    @CommandLine.Option(names = "--ticket", required = true, description = "Ticket number")
    String ticketNo;

    @CommandLine.Option(names = "--round", description = "Round (default: latest)")
    Integer round;

    @Override
    public void run() {
        GateComponents c = components();
        PublishResult r = c.gateService().publish(new PublishCommand(ticketNo, round));
        JsonOut.emit(System.out, "publish", Map.of(
                "ticket_no", r.ticketNo(),
                "review_round", r.reviewRound(),
                "tree_hash", r.treeHash(),
                "commit_sha", r.commitSha(),
                "target_ref", r.targetRef(),
                "ref_before", r.refBefore(),
                "ref_after", r.refAfter(),
                "already_published", r.alreadyPublished()));
    }
}
