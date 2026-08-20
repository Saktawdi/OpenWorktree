package gate.web;

import gate.adapters.config.TomlGateConfigLoader;
import gate.adapters.git.GitCli;
import gate.adapters.lock.FileChannelTicketLockManager;
import gate.adapters.process.CliLocator;
import gate.adapters.session.ClaudeHeadlessAdapter;
import gate.adapters.session.DispatchAgentSessionPort;
import gate.adapters.session.OpenCodeServeAdapter;
import gate.adapters.session.PortAllocator;
import gate.adapters.store.JdbcAgentConfigRepository;
import gate.adapters.store.JdbcProjectRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.JdbcSessionRepository;
import gate.bootstrap.GateRuntime;
import gate.application.GateService;
import gate.application.MetricsService;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.policy.GatePolicy;
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
import gate.adapters.metrics.AlertService;
import gate.adapters.metrics.InMemoryMetrics;
import gate.adapters.health.HealthService;
import gate.adapters.backup.BackupService;
import gate.ports.metrics.MetricsPort;
import gate.ports.metrics.TracingPort;
import gate.ports.security.AuditArchivePort;
import gate.ports.security.RbacPort;
import gate.ports.security.TenantPort;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Assembles the gate-web object graph by hand (执行文档-后端-web §2.2), mirroring the wiring style of
 * {@code gate.cli.GateComponents}.
 *
 * <p>gate-web is a driver adapter on the same level as gate-cli, so it cannot depend on
 * {@code GateComponents}; it reconstructs the identical graph from the same adapters. This is
 * deliberate — the Web console reuses {@link GateService}, {@link CredentialRepository} (HUMAN
 * domain, ADR-10) and every existing adapter wholesale, adding only the web/session components.
 *
 * <p>Every gate instance = one {@code gate.toml} = one {@code WebComponents} (single-process
 * single-project, ADR-14).
 */
public final class WebComponents {

    private final GateConfig config;
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
    private final gate.ports.ProjectRepository projectRepository;
    private final ProviderModelFetcher modelFetcher;
    private final RuntimeInfoService runtimeInfo;
    // Phase4
    private final MetricsPort metricsPort;
    private final InMemoryMetrics inMemoryMetrics;
    private final TracingPort tracingPort;
    private final RbacPort rbacPort;
    private final TenantPort tenantPort;
    private final AuditArchivePort auditArchivePort;
    private final HealthService healthService;
    private final BackupService backupService;
    private final gate.application.metrics.SloService sloService;
    private final AlertService alertService;
    private final gate.web.health.HealthRoutes healthRoutes;
    private final gate.web.metrics.MetricsRoutes metricsRoutes;
    private final gate.web.provider.ProviderRoutes providerRoutes;
    private final gate.web.security.SecurityContextResolver securityResolver;
    private final gate.application.security.RbacService rbacService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public WebComponents(GateConfig config, String gitExecutable, Path envFile) {
        this(config, gitExecutable, envFile, null);
    }

    WebComponents(GateConfig config, String gitExecutable, Path envFile, AgentSessionPort sessionPortOverride) {
        if (!config.webConfigured()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "gate-web requires a [web] section in gate.toml (执行文档-后端-web §8.1); none was found");
        }
        GateRuntime runtime = new GateRuntime(config, gitExecutable, envFile);
        this.config = runtime.config();
        this.clock = runtime.clock();
        this.envFile = runtime.envFile();
        this.startedAt = this.clock.now();
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
        BlobStore blobStore = this.blobStore;
        seedCliDefaultProvider();
        // Composition root: reuse ports from GateRuntime, do not recreate DataSource/JdbcTemplate here
        gate.adapters.store.JdbcGateTaskRepository gateTasks = runtime.gateTaskRepository();
        gateTasks.failOrphaned(clock.now());
        this.taskRegistry = runtime.taskRegistry();
        // Phase4 wiring
        this.metricsPort = runtime.metricsPort();
        this.inMemoryMetrics = (InMemoryMetrics) runtime.metricsPort();
        this.tracingPort = runtime.tracingPort();
        this.rbacPort = runtime.rbacPort();
        this.tenantPort = runtime.tenantPort();
        this.auditArchivePort = runtime.auditArchivePort();
        this.healthService = runtime.healthService();
        this.backupService = runtime.backupService();
        this.sloService = runtime.sloService();
        this.alertService = runtime.alertService();
        this.healthRoutes = new gate.web.health.HealthRoutes(healthService);
        this.metricsRoutes = new gate.web.metrics.MetricsRoutes(inMemoryMetrics, sloService);
        this.providerRoutes = new gate.web.provider.ProviderRoutes(providerRepository, clock);
        // Tenant-aware resolver needs DB for credential.tenant_id lookup (V13)
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(runtime.dataSource());
        this.securityResolver = new gate.web.security.SecurityContextResolver(credentials, rbacPort, this.jdbc);
        this.rbacService = new gate.application.security.RbacService(rbacPort);

        this.agentConfigRepository = runtime.agentConfigRepository();
        this.projectRepository = runtime.projectRepository();
        this.modelFetcher = new ProviderModelFetcher(envFile);
        gate.ports.SessionRepository sessionRepo = runtime.sessionRepository();
        // abort orphaned via concrete type if available
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
            // Resolve to the real file (npm's claude.cmd / opencode.cmd on Windows) — ProcessBuilder
            // cannot launch a bare .cmd name, which would break every session start on Windows.
            String claudeCmd = cliLocator.locate("claude").map(Path::toString).orElse("claude");
            String opencodeCmd = cliLocator.locate("opencode").map(Path::toString).orElse("opencode");
            this.claudeAdapter = new ClaudeHeadlessAdapter(processRunner, this.agentConfigRepository, sessionRepo,
                    tickets, taskRegistry, ticketLockManager, clock, claudeCmd);
            int startTimeout = config.session() == null ? 60 : config.session().startTimeoutSeconds();
            this.opencodeAdapter = new OpenCodeServeAdapter(processRunner, this.agentConfigRepository, sessionRepo,
                    tickets, taskRegistry, ticketLockManager, clock, portAllocator, opencodeCmd,
                    startTimeout);
            this.agentSessionPort = new DispatchAgentSessionPort(this.agentConfigRepository, sessionRepo,
                    claudeAdapter, opencodeAdapter);
        }
    }

    /**
     * Loads config, builds the graph, and seeds the {@code manual} provider (same bootstrap as
     * {@code GateComponents.fromConfig}). {@code .env} is anchored to {@code gate.toml}'s directory.
     */
    public static WebComponents fromConfig(Path tomlPath, String gitExecutable) {
        GateConfig config = new TomlGateConfigLoader().load(tomlPath);
        Path envFile = tomlPath.toAbsolutePath().getParent().resolve(".env");
        WebComponents components = new WebComponents(config, gitExecutable, envFile);
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

    /** Private FK target used when a local CLI profile leaves provider/model under CLI control. */
    private void seedCliDefaultProvider() {
        if (providerRepository.find("cli-default").isEmpty()) {
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    "cli-default", "CLI default (internal)", "local://cli-default", "none", "cli-runtime",
                    clock.now()), clock.now());
        }
    }

    public GateConfig config() {
        return config;
    }

    public GateService gateService() {
        return gateService;
    }

    public MetricsService metricsService() {
        return metricsService;
    }

    public TopologyInitializer topologyInitializer() {
        return topologyInitializer;
    }

    public PreflightChecker preflightChecker() {
        return preflightChecker;
    }

    public ProviderRepository providerRepository() {
        return providerRepository;
    }

    public TicketRepository ticketRepository() {
        return ticketRepository;
    }

    public PresubmitRepository presubmitRepository() {
        return presubmitRepository;
    }

    public ReviewResultRepository reviewResultRepository() {
        return reviewResultRepository;
    }

    public PublishIntentRepository publishIntentRepository() {
        return publishIntentRepository;
    }

    public BlobStore blobStore() {
        return blobStore;
    }

    public CredentialRepository credentials() {
        return credentials;
    }

    public Clock clock() {
        return clock;
    }

    public Path envFile() {
        return envFile;
    }

    public TaskRegistry taskRegistry() {
        return taskRegistry;
    }

    public TaskRunner taskRunner() {
        return taskRunner;
    }

    public AgentConfigRepository agentConfigRepository() {
        return agentConfigRepository;
    }

    public SessionRepository sessionRepository() {
        return sessionRepository;
    }

    public AgentSessionPort agentSessionPort() {
        return agentSessionPort;
    }

    public TicketLockManager ticketLockManager() {
        return ticketLockManager;
    }

    public gate.ports.ProjectRepository projectRepository() {
        return projectRepository;
    }

    public ProviderModelFetcher modelFetcher() {
        return modelFetcher;
    }

    public RuntimeInfoService runtimeInfo() {
        return runtimeInfo;
    }

    public GitCli git() {
        return git;
    }

    public ProcessRunner processRunner() {
        return processRunner;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public MetricsPort metricsPort() { return metricsPort; }
    public InMemoryMetrics inMemoryMetrics() { return inMemoryMetrics; }
    public TracingPort tracingPort() { return tracingPort; }
    public RbacPort rbacPort() { return rbacPort; }
    public TenantPort tenantPort() { return tenantPort; }
    public AuditArchivePort auditArchivePort() { return auditArchivePort; }
    public HealthService healthService() { return healthService; }
    public BackupService backupService() { return backupService; }
    public gate.application.metrics.SloService sloService() { return sloService; }
    public AlertService alertService() { return alertService; }
    public gate.web.health.HealthRoutes healthRoutes() { return healthRoutes; }
    public gate.web.metrics.MetricsRoutes metricsRoutes() { return metricsRoutes; }
    public gate.web.provider.ProviderRoutes providerRoutes() { return providerRoutes; }
    public gate.web.security.SecurityContextResolver securityResolver() { return securityResolver; }
    public gate.application.security.RbacService rbacService() { return rbacService; }
    public org.springframework.jdbc.core.JdbcTemplate jdbc() { return jdbc; }

    /** Shuts down the async task executor and session adapters. Idempotent; called by {@link WebServer#close()}. */
    public void close() {
        taskRunner.close();
        if (claudeAdapter != null) {
            claudeAdapter.close();
        }
        if (opencodeAdapter != null) {
            opencodeAdapter.close();
        }
    }
}
