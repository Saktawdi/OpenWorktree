package gate.cli;

import gate.domain.git.RepoRef;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate ticket create}: register a ticket and create its independent clone (§2.2).
 *
 * <p>The clone is always {@code clone --no-hardlinks --single-branch --branch <target>}, never a
 * linked worktree — enforced by the topology initializer, checked again by preflight.
 */
@CommandLine.Command(name = "ticket", description = "Ticket operations",
        subcommands = {TicketCommand.Create.class})
final class TicketCommand {

    @CommandLine.Command(name = "create", description = "Register a ticket and create its independent clone")
    static final class Create extends BaseCommand {

        @CommandLine.Option(names = "--ticket", required = true, description = "Ticket number, e.g. TICKET-1")
        String ticketNo;

        @CommandLine.Option(names = "--title", defaultValue = "", description = "Ticket title")
        String title;

        @Override
        public void run() {
            GateComponents c = components();
            String targetRef = c.config().primaryTargetRef();
            RepoRef auth = RepoRef.of(c.config().authRepo());
            Path cloneDir = c.config().clonesRoot().resolve(ticketNo);

            RepoRef clone = c.topologyInitializer().createClone(auth, targetRef, cloneDir);
            Instant now = c.clock().now();
            c.ticketRepository().insert(new Ticket(ticketNo, title, targetRef, clone.pathString(),
                    null, null, "manual", "human", TicketStage.IN_PROGRESS, now, now));

            JsonOut.emit(System.out, "ticket.create", Map.of(
                    "ticket_no", ticketNo,
                    "target_ref", targetRef,
                    "clone_path", clone.pathString(),
                    "stage", TicketStage.IN_PROGRESS.name()));
        }
    }
}
