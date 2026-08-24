package gate.web;

import gate.adapters.config.TomlGateConfigLoader;
import gate.adapters.git.GitCli;
import gate.adapters.io.AdapterLog;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.CliLocator;
import gate.adapters.session.ClaudeHeadlessAdapter;
import gate.adapters.session.DispatchAgentSessionPort;
import gate.adapters.session.OpenCodeServeAdapter;
import gate.adapters.session.PortAllocator;
import gate.bootstrap.GateRuntime;
import gate.application.GateService;
import gate.application.MetricsService;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.AgentConfigRepository;
import gate.ports.AgentSessionPort;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.CredentialRepository;
import gate.ports.PreflightChecker;
import gate.ports.PresubmitRepository;
import gate.ports.ProcessRunner;
import gate.ports.ProviderRepository;
import gate.ports.PublishIntentRepository;
import gate.ports.ReviewResultRepository;
import gate.ports.SessionRepository;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import gate.web.service.ProviderModelFetcher;
import gate.web.service.RuntimeInfoService;
import gate.web.service.TaskRunner;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Assembles the gate-web object graph by hand.
 */
public final class WebComponents {

    private final GateConfig config;
    private boolean closed;
    private final GateService gateService;
    private final MetricsService metricsService;
    private final TopologyInitializer topologyInitializer;
    private final PreflightChecker preflightChecker;
    private final ProviderRepository providerRepository;
    private final TicketRepository ticketRepository;
    private final PresubmitRepository presubmitRepository;
    private final ReviewResultRepository reviewResultRepository;
    private final PublishIntentRepository publishIntentRepository;
    private final BlobStore blobStore;
    private final CredentialRepository credentials;
    private final TaskRegistry taskRegistry;
    private final TaskRunner taskRunner;
    private final AgentConfigRepository agentConfigRepository;
    private final SessionRepository sessionRepository;
    private final AgentSessionPort agentSessionPort;
    private final ClaudeHeadlessAdapter claudeAdapter;
    private final OpenCodeServeAdapter opencodeAdapter;
    private final TicketLockManager ticketLockManager;
    private final Clock clock;
    private final Path envFile;
    private final ProcessRunner processRunner;
    private final GitCli git;
    private final Instant startedAt;
    private final Path gateToml;
    private final gate.ports.ProjectRepository projectRepository;
    private final gate.ports.WorkspaceSyncer workspaceSyncer;
    private final ProviderModelFetcher modelFetcher;
    private final RuntimeInfoService runtimeInfo;

    public WebComponents(GateConfig config, String gitExecutable, Path envFile) {
        this(config, gitExecutable, envFile, null, null);
    }

    WebComponents(GateConfig config, String gitExecutable, Path envFile, Path gateToml) {
        this(config, gitExecutable, envFile, gateToml, null);
    }

    WebComponents(GateConfig config, String gitExecutable, Path envFile, Path gateToml,
                  AgentSessionPort sessionPortOverride) {
        if (!config.webConfigured()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "gate-web requires a [web] section in gate.toml; none was found");
        }
        GateRuntime runtime = new GateRuntime(config, gitExecutable, envFile);
        this.config = runtime.config();
        this.clock = runtime.clock();
        this.envFile = runtime.envFile();
        this.startedAt = this.clock.now();
        this.gateToml = gateToml == null ? null : gateToml.toAbsolutePath().normalize();
        this.processRunner = runtime.processRunner();
        this.git = runtime.git();
        this.gateService = runtime.gateService();
        this.topologyInitializer = runtime.topologyInitializer();
        this.preflightChecker = runtime.preflightChecker();
        this.providerRepository = runtime.providerRepository();
        this.ticketRepository = runtime.ticketRepository();
        this.presubmitRepository = runtime.presubmitRepository();
        this.reviewResultRepository = runtime.reviewResultRepository();
        this.publishIntentRepository = runtime.publishIntentRepository();
        this.blobStore = runtime.blobStore();
        this.credentials = runtime.credentials();
        this.ticketLockManager = new FileChannelTicketLockManager(config.locksDir().resolve("tickets"));

        TicketRepository tickets = this.ticketRepository;
        seedCliDefaultProvider();
        gate.adapters.store.JdbcGateTaskRepository gateTasks = runtime.gateTaskRepository();
        gateTasks.failOrphaned(clock.now());
        this.taskRegistry = runtime.taskRegistry();

        this.agentConfigRepository = runtime.agentConfigRepository();
        this.projectRepository = runtime.projectRepository();
        this.workspaceSyncer = runtime.workspaceSyncer();
        this.modelFetcher = new ProviderModelFetcher(envFile);
        gate.ports.SessionRepository sessionRepo = runtime.sessionRepository();
        if (sessionRepo instanceof gate.adapters.store.JdbcSessionRepository jsr) {
            jsr.abortOrphanedActive(clock.now());
        }
        this.sessionRepository = sessionRepo;

        this.metricsService = new MetricsService(reviewResultRepository, presubmitRepository, ticketRepository);
        this.taskRunner = new TaskRunner(taskRegistry, gateService, clock, ticketLockManager);
        CliLocator cliLocator = new CliLocator(processRunner);
        this.runtimeInfo = new RuntimeInfoService(config, gitExecutable, processRunner, cliLocator,
                gateService, tickets, sessionRepository, taskRegistry, startedAt, this.clock::now);
        if (sessionPortOverride != null) {
            this.claudeAdapter = null;
            this.opencodeAdapter = null;
            this.agentSessionPort = sessionPortOverride;
        } else {
            int portMin = config.session() == null ? 49152 : config.session().portRangeMin();
            int portMax = config.session() == null ? 65535 : config.session().portRangeMax();
            PortAllocator portAllocator = new PortAllocator(portMin, portMax);
            String claudeCmd = cliLocator.locate("claude").map(Path::toString).orElse("claude");
            String opencodeCmd = cliLocator.locate("opencode").map(Path::toString).orElse("opencode");
            this.claudeAdapter = new ClaudeHeadlessAdapter(processRunner, this.agentConfigRepository, sessionRepo,
                    tickets, this.projectRepository, taskRegistry, ticketLockManager, clock, claudeCmd, List.of(),
                    gateToml);
            int startTimeout = config.session() == null ? 60 : config.session().startTimeoutSeconds();
            AdapterLog adapterLog = AdapterLog.at(config.gateHome().resolve("adapters.log"));
            gate.adapters.io.ServePidRegistry pidRegistry =
                    new gate.adapters.io.ServePidRegistry(config.gateHome().resolve("opencode-serve.pids"));
            this.opencodeAdapter = new OpenCodeServeAdapter(processRunner, this.agentConfigRepository, sessionRepo,
                    tickets, this.projectRepository, taskRegistry, ticketLockManager, clock, portAllocator, opencodeCmd,
                    startTimeout, adapterLog, pidRegistry, gateToml);
            this.agentSessionPort = new DispatchAgentSessionPort(this.agentConfigRepository, sessionRepo,
                    claudeAdapter, opencodeAdapter);
        }
    }

    public static WebComponents fromConfig(Path tomlPath, String gitExecutable) {
        GateConfig config = new TomlGateConfigLoader().load(tomlPath);
        Path envFile = tomlPath.toAbsolutePath().getParent().resolve(".env");
        WebComponents components = new WebComponents(config, gitExecutable, envFile,
                tomlPath.toAbsolutePath().normalize());
        components.seedManualProvider();
        return components;
    }

    private void seedManualProvider() {
        if (providerRepository.find("manual").isEmpty()) {
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    "manual", "manual (human reviewer)", "local://manual", "none", "manual", clock.now()),
                    clock.now());
        }
    }

    private void seedCliDefaultProvider() {
        if (providerRepository.find("cli-default").isEmpty()) {
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    "cli-default", "CLI default (internal)", "local://cli-default", "none", "cli-runtime",
                    clock.now()), clock.now());
        }
    }

    public GateConfig config() { return config; }
    public GateService gateService() { return gateService; }
    public MetricsService metricsService() { return metricsService; }
    public TopologyInitializer topologyInitializer() { return topologyInitializer; }
    public PreflightChecker preflightChecker() { return preflightChecker; }
    public ProviderRepository providerRepository() { return providerRepository; }
    public TicketRepository ticketRepository() { return ticketRepository; }
    public PresubmitRepository presubmitRepository() { return presubmitRepository; }
    public ReviewResultRepository reviewResultRepository() { return reviewResultRepository; }
    public PublishIntentRepository publishIntentRepository() { return publishIntentRepository; }
    public BlobStore blobStore() { return blobStore; }
    public CredentialRepository credentials() { return credentials; }
    public Clock clock() { return clock; }
    public Path envFile() { return envFile; }
    public TaskRegistry taskRegistry() { return taskRegistry; }
    public TaskRunner taskRunner() { return taskRunner; }
    public AgentConfigRepository agentConfigRepository() { return agentConfigRepository; }
    public SessionRepository sessionRepository() { return sessionRepository; }
    public AgentSessionPort agentSessionPort() { return agentSessionPort; }
    public TicketLockManager ticketLockManager() { return ticketLockManager; }
    public gate.ports.ProjectRepository projectRepository() { return projectRepository; }
    public gate.ports.WorkspaceSyncer workspaceSyncer() { return workspaceSyncer; }
    public ProviderModelFetcher modelFetcher() { return modelFetcher; }
    public RuntimeInfoService runtimeInfo() { return runtimeInfo; }
    public GitCli git() { return git; }
    public ProcessRunner processRunner() { return processRunner; }
    public Instant startedAt() { return startedAt; }
    public Path gateToml() { return gateToml; }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        taskRunner.close();
        if (claudeAdapter != null) {
            claudeAdapter.close();
        }
        if (opencodeAdapter != null) {
            opencodeAdapter.close();
        }
    }
}
