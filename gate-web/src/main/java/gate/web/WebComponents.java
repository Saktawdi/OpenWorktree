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
import gate.application.metrics.MetricsService;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.store.AgentConfigRepository;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.store.CredentialRepository;
import gate.ports.engine.PreflightChecker;
import gate.ports.store.PresubmitRepository;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProviderRepository;
import gate.ports.store.PublishIntentRepository;
import gate.ports.store.ReviewResultRepository;
import gate.ports.store.SessionRepository;
import gate.ports.task.TaskRegistry;
import gate.ports.infra.TicketLockManager;
import gate.ports.store.TicketRepository;
import gate.ports.git.TopologyInitializer;
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
    private final ProcessRunner processRunner;
    private final GitCli git;
    private final Instant startedAt;
    private final Path gateToml;
    private final gate.ports.store.ProjectRepository projectRepository;
    private final gate.ports.git.WorkspaceSyncer workspaceSyncer;
    private final gate.ports.git.CloneBaseSyncer cloneBaseSyncer;
    private final gate.ports.infra.KmsService kmsService;
    private final ProviderModelFetcher modelFetcher;
    private final RuntimeInfoService runtimeInfo;
    private final gate.ports.store.TicketStageChangeRepository ticketStageChangeRepository;
    private final gate.ports.store.AuditLog auditLog;

    public WebComponents(GateConfig config, String gitExecutable) {
        this(config, gitExecutable, null, null);
    }

    WebComponents(GateConfig config, String gitExecutable, Path gateToml) {
        this(config, gitExecutable, gateToml, null);
    }

    WebComponents(GateConfig config, String gitExecutable, Path gateToml,
                  AgentSessionPort sessionPortOverride) {
        if (!config.webConfigured()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "gate-web requires a [web] section in gate.toml; none was found");
        }
        GateRuntime runtime = new GateRuntime(config, gitExecutable);
        this.config = runtime.config();
        this.clock = runtime.clock();
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
        this.ticketStageChangeRepository = runtime.ticketStageChangeRepository();
        this.auditLog = runtime.auditLog();
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
        seedDefaultAgentConfig();
        this.projectRepository = runtime.projectRepository();
        this.workspaceSyncer = runtime.workspaceSyncer();
        this.cloneBaseSyncer = runtime.cloneBaseSyncer();
        this.kmsService = runtime.kmsService();
        this.modelFetcher = new ProviderModelFetcher(this.kmsService);
        gate.ports.store.SessionRepository sessionRepo = runtime.sessionRepository();
        // 旧版启动例程会把所有 ACTIVE 会话清扫成 ABORTED（前提是"会话活不过重启"）。
        // 懒复活机制下该前提不再成立：ACTIVE 会话在重启后仍可按行重建 serve 续接，
        // 清扫反而把可复活的会话错误标记为终态——已移除。
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
            // T-118: every session start re-syncs the clone base onto the project's base branch tip
            // (auto = allowDirty=false; a dirty worktree is left for the manual sync endpoint).
            gate.ports.git.BaseSynchronizer baseSynchronizer =
                    (no, allowDirty) -> this.gateService.syncBase(
                            new gate.application.basesync.SyncBaseCommand(no, allowDirty, "auto"));
            this.claudeAdapter = new ClaudeHeadlessAdapter(processRunner, this.agentConfigRepository, sessionRepo,
                    tickets, this.projectRepository, this.ticketStageChangeRepository, taskRegistry, ticketLockManager,
                    clock, claudeCmd, List.of(), gateToml, baseSynchronizer);
            int startTimeout = config.session() == null ? 60 : config.session().startTimeoutSeconds();
            AdapterLog adapterLog = AdapterLog.at(config.gateHome().resolve("adapters.log"));
            gate.adapters.io.ServePidRegistry pidRegistry =
                    new gate.adapters.io.ServePidRegistry(config.gateHome().resolve("opencode-serve.pids"));
            this.opencodeAdapter = new OpenCodeServeAdapter(processRunner, this.agentConfigRepository, sessionRepo,
                    tickets, this.projectRepository, this.ticketStageChangeRepository, taskRegistry, ticketLockManager,
                    clock, portAllocator, opencodeCmd, startTimeout, adapterLog, pidRegistry, gateToml,
                    this.credentials, baseSynchronizer);
            this.agentSessionPort = new DispatchAgentSessionPort(this.agentConfigRepository, sessionRepo,
                    claudeAdapter, opencodeAdapter);
        }
    }

    public static WebComponents fromConfig(Path tomlPath, String gitExecutable) {
        GateConfig config = new TomlGateConfigLoader().load(tomlPath);
        WebComponents components = new WebComponents(config, gitExecutable,
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

    /**
     * 首次使用预置一条默认的 opencode 智能体员工：provider/model 留空 = 完全交给 opencode
     * 自身配置决定，开箱即可在工单上发起会话，不必先手工建配置。幂等；用户删掉后不再复活，
     * 与用户自建配置无任何区别。
     */
    private void seedDefaultAgentConfig() {
        String defaultId = "opencode-default";
        if (agentConfigRepository.find(defaultId).isPresent()
                || !agentConfigRepository.findAll().isEmpty()) {
            return;
        }
        agentConfigRepository.insert(new gate.domain.session.AgentConfig(
                defaultId,
                "默认智能体",
                gate.domain.session.AgentCli.OPENCODE,
                null,
                null,
                null,
                java.util.List.of(),
                "首次启动预置：跟随本机 opencode 自身配置（provider/model 由 CLI 决定）",
                true),
                clock.now());
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
    public TaskRegistry taskRegistry() { return taskRegistry; }
    public TaskRunner taskRunner() { return taskRunner; }
    public AgentConfigRepository agentConfigRepository() { return agentConfigRepository; }
    public SessionRepository sessionRepository() { return sessionRepository; }
    public AgentSessionPort agentSessionPort() { return agentSessionPort; }
    public TicketLockManager ticketLockManager() { return ticketLockManager; }
    public gate.ports.store.ProjectRepository projectRepository() { return projectRepository; }
    public gate.ports.git.WorkspaceSyncer workspaceSyncer() { return workspaceSyncer; }
    public gate.ports.git.CloneBaseSyncer cloneBaseSyncer() { return cloneBaseSyncer; }
    public gate.ports.infra.KmsService kmsService() { return kmsService; }
    public ProviderModelFetcher modelFetcher() { return modelFetcher; }
    public RuntimeInfoService runtimeInfo() { return runtimeInfo; }
    public GitCli git() { return git; }
    public ProcessRunner processRunner() { return processRunner; }
    public Instant startedAt() { return startedAt; }
    public Path gateToml() { return gateToml; }

    public gate.ports.store.TicketStageChangeRepository ticketStageChangeRepository() { return ticketStageChangeRepository; }
    public gate.ports.store.AuditLog auditLog() { return auditLog; }

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
