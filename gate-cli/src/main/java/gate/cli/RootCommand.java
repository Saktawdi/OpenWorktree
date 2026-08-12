package gate.cli;

import picocli.CommandLine;

/** Root command. Subcommands carry the behaviour; this only groups them and prints help. */
@CommandLine.Command(
        name = "gate",
        mixinStandardHelpOptions = true,
        version = "gate 0.1.0 (P1)",
        description = "Local git commit gate — P1 skeleton (no LLM engine, no MCP).",
        subcommands = {
                InitCommand.class,
                PreflightCommand.class,
                TicketCommand.class,
                PresubmitCliCommand.class,
                ReviewCliCommand.class,
                PublishCliCommand.class,
                ReconcileCliCommand.class,
                StatusCliCommand.class
        })
final class RootCommand implements Runnable {

    @Override
    public void run() {
        // No subcommand: print usage to stderr and let the caller pick.
        CommandLine.usage(this, System.err);
    }
}
