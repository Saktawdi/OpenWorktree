package gate.cli;

import picocli.CommandLine;

/** Root command. Subcommands carry the behaviour; this only groups them and prints help. */
@CommandLine.Command(
        name = "gate",
        mixinStandardHelpOptions = true,
        version = "gate 0.2.0 (P2)",
        description = "Local git commit gate — P2 (prism engine wired into ReviewEngine).",
        subcommands = {
                InitCommand.class,
                PreflightCommand.class,
                TicketCommand.class,
                PresubmitCliCommand.class,
                ReviewCliCommand.class,
                PublishCliCommand.class,
                ReconcileCliCommand.class,
                StatusCliCommand.class,
                ProviderCommand.class
        })
final class RootCommand implements Runnable {

    @Override
    public void run() {
        // No subcommand: print usage to stderr and let the caller pick.
        CommandLine.usage(this, System.err);
    }
}
