package gate.web;

import gate.adapters.approval.FsApprovalStore;
import gate.adapters.audit.HashChainAuditLog;
import gate.adapters.blob.FsBlobStore;
import gate.adapters.clock.SystemClock;
import gate.adapters.config.TomlGateConfigLoader;
import gate.adapters.engine.GateReviewEngineFactory;
import gate.adapters.engine.ManualReviewEngineFactory;
import gate.adapters.git.GitCli;
import gate.adapters.git.GitCliPublisher;
import gate.adapters.git.GitCliRefObserver;
import gate.adapters.git.GitCliSnapshot;
import gate.adapters.git.GitCliTopologyInitializer;
import gate.adapters.hook.FileHookInstaller;
import gate.adapters.lock.FileChannelLockManager;
import gate.adapters.preflight.DefaultPreflightChecker;
import gate.adapters.process.ProcessRunnerImpl;
import gate.adapters.store.JdbcCredentialRepository;
import gate.adapters.store.JdbcPresubmitRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.JdbcPublishIntentRepository;
import gate.adapters.store.JdbcReviewResultRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SpringDbTransactionRunner;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.application.GateService;
import gate.application.GateServiceImpl;
import gate.application.MetricsService;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.policy.GatePolicy;
import gate.ports.ApprovalStore;
import gate.ports.AuditLog;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.CommitPublisher;
import gate.ports.CredentialRepository;
import gate.ports.DbTransactionRunner;
import gate.ports.HookInstaller;
import gate.ports.LockManager;
import gate.ports.PreflightChecker;
import gate.ports.PresubmitRepository;
import gate.ports.ProcessRunner;
import gate.ports.ProviderRepository;
import gate.ports.PublishIntentRepository;
import gate.ports.RefObserver;
import gate.ports.ReviewEngineFactory;
import gate.ports.ReviewResultRepository;
import gate.ports.SnapshotCapture;
import gate.ports.TaskRegistry;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final Clock clock;
    private final Path envFile;

    public WebComponents(GateConfig config, String gitExecutable, Path envFile) {
        if (!config.webConfigured()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "gate-web requires a [web] section in gate.toml (执行文档-后端-web §8.1); none was found");
        }
        this.config = config;
        this.clock = new SystemClock();
        this.envFile = envFile;

        ProcessRunner processRunner = new ProcessRunnerImpl(config.gateHome().resolve("proc"));
        GitCli git = new GitCli(processRunner, gitExecutable, Duration.ofSeconds(120));

        SnapshotCapture snapshotCapture = new GitCliSnapshot(git, config.indexDir());
        CommitPublisher commitPublisher = new GitCliPublisher(git, config.indexDir());
        RefObserver refObserver = new GitCliRefObserver(git);
        ApprovalStore approvalStore = new FsApprovalStore(config.approvalsDir());
        HookInstaller hookInstaller = new FileHookInstaller();
        this.topologyInitializer = new GitCliTopologyInitializer(git, hookInstaller,
                config.targetRefWhitelist(), config.gateHome().resolve("tmp"));
        this.preflightChecker = new DefaultPreflightChecker(config, git, hookInstaller, processRunner);

        BlobStore blobStore = new FsBlobStore(config.blobRoot());
        AuditLog auditLog = new HashChainAuditLog(config.auditPath());
        LockManager lockManager = new FileChannelLockManager(config.locksDir());

        DataSource dataSource = SqliteDataSourceFactory.create(config.dbPath());
        SqliteDataSourceFactory.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DbTransactionRunner txRunner = new SpringDbTransactionRunner(txTemplate);

        TicketRepository tickets = new JdbcTicketRepository(jdbc);
        this.ticketRepository = tickets;
        PresubmitRepository presubmits = new JdbcPresubmitRepository(jdbc);
        this.presubmitRepository = presubmits;
        ReviewResultRepository reviewResults = new JdbcReviewResultRepository(jdbc);
        this.reviewResultRepository = reviewResults;
        PublishIntentRepository intents = new JdbcPublishIntentRepository(jdbc, config.authRepo());
        this.publishIntentRepository = intents;
        this.blobStore = blobStore;
        this.providerRepository = new JdbcProviderRepository(jdbc);
        this.credentials = new JdbcCredentialRepository(jdbc);
        JdbcGateTaskRepository gateTasks = new JdbcGateTaskRepository(jdbc, clock);
        gateTasks.failOrphaned(clock.now());
        this.taskRegistry = gateTasks;

        ReviewEngineFactory reviewEngineFactory = config.engineConfigured()
                ? new GateReviewEngineFactory(blobStore, config, processRunner, providerRepository, envFile)
                : new ManualReviewEngineFactory(blobStore);
        GatePolicy gatePolicy = new GatePolicy();

        this.gateService = new GateServiceImpl(config, snapshotCapture, commitPublisher, refObserver,
                approvalStore, reviewEngineFactory, gatePolicy, tickets, presubmits, reviewResults, intents,
                blobStore, auditLog, lockManager, txRunner, clock);
        this.metricsService = new MetricsService(reviewResults, presubmits, tickets);
        this.taskRunner = new TaskRunner(taskRegistry, gateService, clock);
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

    /** Shuts down the async task executor. Idempotent; called by {@link WebServer#close()}. */
    public void close() {
        taskRunner.close();
    }
}
