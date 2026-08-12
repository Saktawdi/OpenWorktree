package gate.cli;

import gate.domain.config.GateConfig;
import gate.domain.policy.GatePolicy;
import gate.domain.git.RepoRef;
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
import gate.adapters.store.JdbcPresubmitRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcPublishIntentRepository;
import gate.adapters.store.JdbcReviewResultRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SpringDbTransactionRunner;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.application.GateService;
import gate.application.GateServiceImpl;
import gate.ports.ApprovalStore;
import gate.ports.AuditLog;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.CommitPublisher;
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
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Assembles the object graph by hand (constructor injection only — §4.4).
 *
 * <p>Spring is deliberately kept to a thin shell (see {@code GateApp}); this factory is where the
 * wiring lives, so the domain and application layers never see a Spring annotation and the whole DI
 * choice stays reversible. The tests use this same factory (no Spring context) to build a live gate
 * over a temp directory, which also proves the layering is honest.
 */
public final class GateComponents {

    private final GateConfig config;
    private final GateService gateService;
    private final TopologyInitializer topologyInitializer;
    private final PreflightChecker preflightChecker;
    private final ProviderRepository providerRepository;
    private final TicketRepository ticketRepository;
    private final Clock clock;
    private final java.nio.file.Path envFile;

    public GateComponents(GateConfig config, String gitExecutable) {
        this(config, gitExecutable, defaultEnvFile());
    }

    public GateComponents(GateConfig config, String gitExecutable, java.nio.file.Path envFile) {
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
        ReviewResultRepository reviewResults = new JdbcReviewResultRepository(jdbc);
        PublishIntentRepository intents = new JdbcPublishIntentRepository(jdbc, config.authRepo());
        this.providerRepository = new JdbcProviderRepository(jdbc);

        ReviewEngineFactory reviewEngineFactory = config.engineConfigured()
                ? new GateReviewEngineFactory(blobStore, config, processRunner, providerRepository, envFile)
                : new ManualReviewEngineFactory(blobStore);
        GatePolicy gatePolicy = new GatePolicy();

        this.gateService = new GateServiceImpl(config, snapshotCapture, commitPublisher, refObserver,
                approvalStore, reviewEngineFactory, gatePolicy, tickets, presubmits, reviewResults, intents,
                blobStore, auditLog, lockManager, txRunner, clock);
    }

    /**
     * Loads config, builds the graph, and seeds the {@code manual} provider so the human verdict can
     * satisfy {@code review_result.provider_id} without weakening the NOT NULL constraint.
     *
     * <p>{@code .env} is resolved relative to {@code gate.toml}'s directory (the project root), not the
     * working directory: a caller may invoke {@code gate} from anywhere, and the secrets file must stay
     * anchored to the project.
     */
    public static GateComponents fromConfig(Path tomlPath, String gitExecutable) {
        GateConfig config = new TomlGateConfigLoader().load(tomlPath);
        Path envFile = tomlPath.toAbsolutePath().getParent().resolve(".env");
        GateComponents components = new GateComponents(config, gitExecutable, envFile);
        components.seedManualProvider();
        return components;
    }

    /** The {@code .env} path this graph reads provider secrets from (exposed for CLI subcommands). */
    public Path envFile() {
        return envFile;
    }

    /**
     * Default {@code .env} location: {@code .env} in the current working directory's root. Used only
     * when the path cannot be derived from a config file (e.g. tests).
     */
    static Path defaultEnvFile() {
        return java.nio.file.Paths.get(".env").toAbsolutePath();
    }

    private void seedManualProvider() {
        if (providerRepository.find("manual").isEmpty()) {
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    "manual", "manual (human reviewer)", "local://manual", "none", "manual", clock.now()), clock.now());
        }
    }

    public GateConfig config() {
        return config;
    }

    public GateService gateService() {
        return gateService;
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

    public Clock clock() {
        return clock;
    }
}
