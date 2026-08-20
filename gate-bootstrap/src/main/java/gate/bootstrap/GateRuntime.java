package gate.bootstrap;

import gate.adapters.approval.FsApprovalStore;
import gate.adapters.audit.HashChainAuditLog;
import gate.adapters.blob.FsBlobStore;
import gate.adapters.clock.SystemClock;
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
import gate.adapters.git.LocalAuthoritativeGitService;
import gate.adapters.kms.LocalKmsService;
import gate.adapters.s3.FsS3Store;
import gate.adapters.store.JdbcCredentialRepository;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.JdbcNonceStore;
import gate.adapters.store.JdbcPresubmitRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcPublishIntentRepository;
import gate.adapters.store.JdbcReviewResultRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SpringDbTransactionRunner;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.adapters.workspace.FsEphemeralWorkspaceManager;
import gate.application.GateService;
import gate.application.GateServiceImpl;
import gate.domain.config.GateConfig;
import gate.domain.policy.GatePolicy;
import gate.ports.ApprovalStore;
import gate.ports.AuditLog;
import gate.ports.AuthoritativeGitService;
import gate.ports.BlobStore;
import gate.ports.Clock;
import gate.ports.CommitPublisher;
import gate.ports.CredentialRepository;
import gate.ports.DbTransactionRunner;
import gate.ports.EphemeralWorkspaceManager;
import gate.ports.HookInstaller;
import gate.ports.KmsService;
import gate.ports.LockManager;
import gate.ports.PreflightChecker;
import gate.ports.PresubmitRepository;
import gate.ports.ProcessRunner;
import gate.ports.ProviderRepository;
import gate.ports.PublishIntentRepository;
import gate.ports.RefObserver;
import gate.ports.ReviewEngineFactory;
import gate.ports.ReviewResultRepository;
import gate.ports.S3Store;
import gate.ports.SnapshotCapture;
import gate.ports.TaskClaimPort;
import gate.ports.TicketRepository;
import gate.ports.TopologyInitializer;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
// GOV-BOOT-001: bootstrap may use Spring internally but must not expose framework types in its public API

/**
 * Shared composition root for every driver.
 *
 * <p>Only this class decides which infrastructure implementations are active. Drivers receive
 * ports and application services from here, so replacing SQLite, file storage or the Git adapter
 * does not require editing both CLI and Web wiring.
 */
public final class GateRuntime {

    private final GateConfig config;
    private final Path envFile;
    private final Clock clock;
    private final ProcessRunner processRunner;
    private final GitCli git;
    private final DataSource dataSource;
    private final GateService gateService;
    private final TopologyInitializer topologyInitializer;
    private final PreflightChecker preflightChecker;
    private final ProviderRepository providerRepository;
    private final TicketRepository ticketRepository;
    private final PresubmitRepository presubmitRepository;
    private final ReviewResultRepository reviewResultRepository;
    private final PublishIntentRepository publishIntentRepository;
    private final BlobStore blobStore;
    private final CredentialRepository credentials;
    // Phase3
    private final JdbcGateTaskRepository gateTaskRepository;
    private final TaskClaimPort taskClaimPort;
    private final gate.ports.NonceStore nonceStore;
    private final AuthoritativeGitService authoritativeGitService;
    private final EphemeralWorkspaceManager ephemeralWorkspaceManager;
    private final S3Store s3Store;
    private final KmsService kmsService;

    public GateRuntime(GateConfig config, String gitExecutable, Path envFile) {
        this.config = config;
        this.envFile = envFile.toAbsolutePath().normalize();
        this.clock = new SystemClock();

        this.processRunner = new ProcessRunnerImpl(config.gateHome().resolve("proc"));
        this.git = new GitCli(processRunner, gitExecutable, Duration.ofSeconds(120));

        SnapshotCapture snapshotCapture = new GitCliSnapshot(git, config.indexDir());
        CommitPublisher commitPublisher = new GitCliPublisher(git, config.indexDir());
        RefObserver refObserver = new GitCliRefObserver(git);
        ApprovalStore approvalStore = new FsApprovalStore(config.approvalsDir());
        HookInstaller hookInstaller = new FileHookInstaller();
        this.topologyInitializer = new GitCliTopologyInitializer(git, hookInstaller,
                config.targetRefWhitelist(), config.gateHome().resolve("tmp"));
        this.preflightChecker = new DefaultPreflightChecker(config, git, hookInstaller, processRunner);

        this.blobStore = new FsBlobStore(config.blobRoot());
        AuditLog auditLog = new HashChainAuditLog(config.auditPath());
        LockManager lockManager = new FileChannelLockManager(config.locksDir());

        this.dataSource = SqliteDataSourceFactory.create(config.dbPath());
        SqliteDataSourceFactory.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DbTransactionRunner txRunner = new SpringDbTransactionRunner(txTemplate);

        this.ticketRepository = new JdbcTicketRepository(jdbc);
        this.presubmitRepository = new JdbcPresubmitRepository(jdbc);
        this.reviewResultRepository = new JdbcReviewResultRepository(jdbc);
        this.publishIntentRepository = new JdbcPublishIntentRepository(jdbc, config.authRepo());
        this.providerRepository = new JdbcProviderRepository(jdbc);
        this.credentials = new JdbcCredentialRepository(jdbc);

        // Phase3 stores
        this.gateTaskRepository = new JdbcGateTaskRepository(jdbc, clock);
        this.taskClaimPort = this.gateTaskRepository;
        this.nonceStore = new JdbcNonceStore(jdbc);
        this.authoritativeGitService = new LocalAuthoritativeGitService(git, refObserver, nonceStore, lockManager);
        this.ephemeralWorkspaceManager = new FsEphemeralWorkspaceManager(config.clonesRoot(), config.authRepo(), git);
        this.s3Store = new FsS3Store(config.blobRoot().resolve("s3"));
        this.kmsService = new LocalKmsService("local-key-1", "local-secret-for-phase3-hmac");

        ReviewEngineFactory reviewEngineFactory = config.engineConfigured()
                ? new GateReviewEngineFactory(blobStore, config, processRunner, providerRepository, this.envFile)
                : new ManualReviewEngineFactory(blobStore);
        this.gateService = new GateServiceImpl(config, snapshotCapture, commitPublisher, refObserver,
                approvalStore, reviewEngineFactory, new GatePolicy(), ticketRepository, presubmitRepository,
                reviewResultRepository, publishIntentRepository, blobStore, auditLog, lockManager, txRunner, clock, gate.ports.PublishProbe.NOOP, authoritativeGitService);
    }

    public GateConfig config() { return config; }
    public Path envFile() { return envFile; }
    public Clock clock() { return clock; }
    public ProcessRunner processRunner() { return processRunner; }
    public GitCli git() { return git; }
    public DataSource dataSource() { return dataSource; }
    public GateService gateService() { return gateService; }
    public TopologyInitializer topologyInitializer() { return topologyInitializer; }
    public PreflightChecker preflightChecker() { return preflightChecker; }
    public ProviderRepository providerRepository() { return providerRepository; }
    public TicketRepository ticketRepository() { return ticketRepository; }
    public PresubmitRepository presubmitRepository() { return presubmitRepository; }
    public ReviewResultRepository reviewResultRepository() { return reviewResultRepository; }
    public PublishIntentRepository publishIntentRepository() { return publishIntentRepository; }
    public BlobStore blobStore() { return blobStore; }
    public CredentialRepository credentials() { return credentials; }
    // Phase3 getters
    public JdbcGateTaskRepository gateTaskRepository() { return gateTaskRepository; }
    public TaskClaimPort taskClaimPort() { return taskClaimPort; }
    public gate.ports.NonceStore nonceStore() { return nonceStore; }
    public AuthoritativeGitService authoritativeGitService() { return authoritativeGitService; }
    public EphemeralWorkspaceManager ephemeralWorkspaceManager() { return ephemeralWorkspaceManager; }
    public S3Store s3Store() { return s3Store; }
    public KmsService kmsService() { return kmsService; }

    /** Seeds a provider required by the review-result foreign key. Safe to call repeatedly. */
    public void seedProvider(String id, String name, String baseUrl, String apiKeyRef, String type) {
        if (providerRepository.find(id).isEmpty()) {
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    id, name, baseUrl, apiKeyRef, type, clock.now()), clock.now());
        }
    }

    public void seedManualProvider() {
        seedProvider("manual", "manual (human reviewer)", "local://manual", "none", "manual");
    }

    public void seedCliDefaultProvider() {
        seedProvider("cli-default", "CLI default (internal)", "local://cli-default", "none", "cli-runtime");
    }
}
