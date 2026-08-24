package gate.cli.cmd;
import gate.cli.BaseCommand;
import gate.cli.GateComponents;
import gate.cli.util.JsonOut;


import gate.application.status.StatusQuery;
import gate.application.status.StatusResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/** {@code gate status}: read-only projection over the DB plus a live auth.git query. */
@CommandLine.Command(name = "status", description = "Show ticket and authoritative-repo status")
public final class StatusCliCommand extends BaseCommand {

    @CommandLine.Option(names = "--ticket", description = "Limit to one ticket (default: all)")
    String ticketNo;

    @Override
    public void run() {
        GateComponents c = components();
        StatusResult r = c.gateService().status(new StatusQuery(ticketNo));
        List<Map<String, Object>> tickets = new ArrayList<>();
        for (StatusResult.TicketStatus t : r.tickets()) {
            tickets.add(Map.of(
                    "ticket_no", t.ticketNo(),
                    "stage", t.stage(),
                    "latest_round", t.latestRound() == null ? "" : t.latestRound(),
                    "latest_tree", t.latestTreeHash() == null ? "" : t.latestTreeHash(),
                    "intent_status", t.latestIntentStatus() == null ? "" : t.latestIntentStatus(),
                    "commit_sha", t.latestCommitSha() == null ? "" : t.latestCommitSha(),
                    "published_in_auth", t.publishedInAuth()));
        }
        JsonOut.emit(System.out, "status", Map.of(
                "target_ref", r.targetRef(),
                "auth_tip", r.authTip(),
                "auth_commit_count", r.authCommitCount(),
                "tickets", tickets));
    }
}
