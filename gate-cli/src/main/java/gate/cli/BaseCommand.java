package gate.cli;

import java.nio.file.Path;
import picocli.CommandLine;

/**
 * Shared option: where {@code gate.toml} lives. The git executable defaults to {@code git} on PATH
 * but can be overridden for testing or when git is installed off-PATH.
 */
abstract class BaseCommand implements Runnable {

    @CommandLine.Option(names = {"-c", "--config"}, description = "Path to gate.toml", required = true)
    Path configPath;

    @CommandLine.Option(names = "--git", description = "git executable (default: git on PATH)", defaultValue = "git")
    String gitExecutable;

    protected GateComponents components() {
        return GateComponents.fromConfig(configPath, gitExecutable);
    }
}
