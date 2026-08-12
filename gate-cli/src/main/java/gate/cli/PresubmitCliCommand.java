package gate.cli;

import gate.application.PresubmitCommand;
import gate.application.PresubmitResult;
import java.util.Map;
import picocli.CommandLine;

/** {@code gate presubmit}: capture the worktree into an immutable tree (§3.2). */
@CommandLine.Command(name = "presubmit", description = "Freeze the worktree into an immutable tree")
final class PresubmitCliCommand extends BaseCommand {

    @CommandLine.Option(names = "--ticket", required = true, description = "Ticket number")
    String ticketNo;

    @Override
    public void run() {
        GateComponents c = components();
        PresubmitResult r = c.gateService().presubmit(new PresubmitCommand(ticketNo));
        JsonOut.emit(System.out, "presubmit", Map.of(
                "ticket_no", r.ticketNo(),
                "review_round", r.reviewRound(),
                "tree_hash", r.treeHash(),
                "base_commit", r.baseCommit(),
                "target_ref", r.targetRef(),
                "diff_bytes", r.diffBytes(),
                "changed_paths", r.changedPaths(),
                "integrity_warnings", r.integrity().warnings().stream().map(v -> v.rule() + ":" + v.detail()).toList()));
    }
}
