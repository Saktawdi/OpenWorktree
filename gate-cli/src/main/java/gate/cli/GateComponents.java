package gate.cli;

import gate.bootstrap.GateRuntime;
import gate.adapters.config.TomlGateConfigLoader;
import gate.domain.config.GateConfig;
import gate.application.GateService;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.store.CredentialRepository;
import gate.ports.engine.PreflightChecker;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.ProviderRepository;
import gate.ports.store.ReviewResultRepository;
import gate.ports.store.TicketRepository;
import gate.ports.git.TopologyInitializer;
import java.nio.file.Path;

/**
 * CLI view of the shared runtime.
 *
 * <p>The CLI remains source-compatible for commands and tests, but no longer owns an independent
 * object graph. All infrastructure wiring lives in {@link GateRuntime}.
 */
public final class GateComponents {

    private final GateRuntime runtime;

    public GateComponents(GateConfig config, String gitExecutable) {
        this(config, gitExecutable, defaultEnvFile());
    }

    public GateComponents(GateConfig config, String gitExecutable, Path envFile) {
        this.runtime = new GateRuntime(config, gitExecutable, envFile);
    }

    public static GateComponents fromConfig(Path tomlPath, String gitExecutable) {
        GateConfig config = new TomlGateConfigLoader().load(tomlPath);
        Path envFile = tomlPath.toAbsolutePath().getParent().resolve(".env");
        GateComponents components = new GateComponents(config, gitExecutable, envFile);
        components.runtime.seedManualProvider();
        return components;
    }

    static Path defaultEnvFile() {
        return Path.of(".env").toAbsolutePath();
    }

    public Path envFile() { return runtime.envFile(); }
    public GateConfig config() { return runtime.config(); }
    public GateService gateService() { return runtime.gateService(); }
    public TopologyInitializer topologyInitializer() { return runtime.topologyInitializer(); }
    public PreflightChecker preflightChecker() { return runtime.preflightChecker(); }
    public ProviderRepository providerRepository() { return runtime.providerRepository(); }
    public TicketRepository ticketRepository() { return runtime.ticketRepository(); }
    public PresubmitRepository presubmitRepository() { return runtime.presubmitRepository(); }
    public ReviewResultRepository reviewResultRepository() { return runtime.reviewResultRepository(); }
    public BlobStore blobStore() { return runtime.blobStore(); }
    public CredentialRepository credentials() { return runtime.credentials(); }
    public Clock clock() { return runtime.clock(); }
}
