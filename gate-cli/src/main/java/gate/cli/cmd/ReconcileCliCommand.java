package gate.cli.cmd;
import gate.cli.BaseCommand;
import gate.cli.GateComponents;
import gate.cli.util.JsonOut;


import gate.application.status.ReconcileCommand;
import gate.application.status.ReconcileResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/** {@code gate reconcile}: converge pending intents against auth.git (§7.4, A5). */
@CommandLine.Command(name = "reconcile", description = "Converge pending publish intents against auth.git")
public final class ReconcileCliCommand extends BaseCommand {

    @CommandLine.Option(names = "--ticket", description = "Limit to one ticket (default: all pending)")
    String ticketNo;

    @Override
    public void run() {
        GateComponents c = components();
        ReconcileResult r = c.gateService().reconcile(new ReconcileCommand(ticketNo));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (ReconcileResult.IntentOutcome o : r.outcomes()) {
            outcomes.add(Map.of(
                    "intent_id", o.intentId(),
                    "ticket_no", o.ticketNo(),
                    "review_round", o.reviewRound(),
                    "tree_hash", o.treeHash(),
                    "commit_sha", o.commitSha() == null ? "" : o.commitSha(),
                    "from", o.from(),
                    "to", o.to(),
                    "reason", o.reason()));
        }
        JsonOut.emit(System.out, "reconcile", Map.of("outcomes", outcomes));
    }
}
