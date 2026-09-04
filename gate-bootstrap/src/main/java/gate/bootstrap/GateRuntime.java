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
import gate.adapters.git.GitCliWorkspaceSyncer;
import gate.adapters.hook.FileHookInstaller;
import gate.adapters.lock.FileChannelLockManager;
import gate.adapters.preflight.DefaultPreflightChecker;
import gate.adapters.process.ProcessRunnerImpl;
import gate.adapters.git.LocalAuthoritativeGitService;
import gate.adapters.kms.LocalKmsService;
import gate.adapters.s3.FsS3Store;
import gate.adapters.store.JdbcAgentConfigRepository;
import gate.adapters.store.JdbcCredentialRepository;
import gate.adapters.store.JdbcGateTaskRepository;
import gate.adapters.store.JdbcNonceStore;
import gate.adapters.store.JdbcPresubmitRepository;
import gate.adapters.store.JdbcProjectRepository;
import gate.adapters.store.JdbcProviderRepository;
import gate.adapters.store.JdbcPublishIntentRepository;
import gate.adapters.store.JdbcReviewResultRepository;
import gate.adapters.store.JdbcSessionRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.JdbcTicketStageChangeRepository;
import gate.adapters.store.SpringDbTransactionRunner;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.adapters.workspace.FsEphemeralWorkspaceManager;
import gate.application.GateService;
import gate.application.GateServiceImpl;
import gate.domain.config.GateConfig;
import gate.domain.policy.GatePolicy;
import gate.ports.store.ApprovalStore;
import gate.ports.store.AuditLog;
import gate.ports.git.AuthoritativeGitService;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.git.CommitPublisher;
import gate.ports.store.CredentialRepository;
import gate.ports.infra.DbTransactionRunner;
import gate.ports.infra.EphemeralWorkspaceManager;
import gate.ports.git.HookInstaller;
import gate.ports.infra.KmsService;
import gate.ports.infra.LockManager;
import gate.ports.engine.PreflightChecker;
import gate.ports.store.PresubmitRepository;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProviderRepository;
import gate.ports.store.PublishIntentRepository;
import gate.ports.git.RefObserver;
import gate.ports.engine.ReviewEngineFactory;
import gate.ports.store.ReviewResultRepository;
import gate.ports.store.S3Store;
import gate.ports.git.SnapshotCapture;
import gate.ports.task.TaskClaimPort;
import gate.ports.store.TicketRepository;
import gate.ports.git.TopologyInitializer;
import gate.ports.git.WorkspaceSyncer;
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
    private final gate.ports.store.NonceStore nonceStore;
    private final AuthoritativeGitService authoritativeGitService;
    private final EphemeralWorkspaceManager ephemeralWorkspaceManager;
    private final S3Store s3Store;
    private final KmsService kmsService;
    private final gate.ports.store.AgentConfigRepository agentConfigRepository;
    private final gate.ports.store.SessionRepository sessionRepository;
    private final gate.ports.store.ProjectRepository projectRepository;
    private final WorkspaceSyncer workspaceSyncer;
    private final gate.ports.git.CloneBaseSyncer cloneBaseSyncer;
    private final AuditLog auditLog;
    private final gate.ports.store.TicketStageChangeRepository ticketStageChangeRepository;

    public GateRuntime(GateConfig config, String gitExecutable) {
        this.config = config;
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
        this.auditLog = new HashChainAuditLog(config.auditPath());
        LockManager lockManager = new FileChannelLockManager(config.locksDir());

        this.dataSource = SqliteDataSourceFactory.create(config.dbPath());
        SqliteDataSourceFactory.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DbTransactionRunner txRunner = new SpringDbTransactionRunner(txTemplate);

        this.ticketRepository = new JdbcTicketRepository(jdbc);
        this.presubmitRepository = new JdbcPresubmitRepository(jdbc);
        this.ticketStageChangeRepository = new JdbcTicketStageChangeRepository(jdbc);
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
        migrateProviderCredentials();
        this.agentConfigRepository = new JdbcAgentConfigRepository(jdbc);
        this.sessionRepository = new JdbcSessionRepository(jdbc, blobStore);
        this.projectRepository = new JdbcProjectRepository(jdbc);
        this.workspaceSyncer = new GitCliWorkspaceSyncer(git);
        this.cloneBaseSyncer = new gate.adapters.git.GitCliBaseSyncer(git);

        ReviewEngineFactory reviewEngineFactory = config.engineConfigured()
                ? new GateReviewEngineFactory(blobStore, config, providerRepository, this.kmsService)
                : new ManualReviewEngineFactory(blobStore);
        this.gateService = new GateServiceImpl(config, snapshotCapture, commitPublisher, refObserver,
                approvalStore, reviewEngineFactory, new GatePolicy(), ticketRepository, presubmitRepository,
                reviewResultRepository, publishIntentRepository, blobStore, auditLog, lockManager, txRunner, clock, gate.ports.engine.PublishProbe.NOOP, authoritativeGitService,
                projectRepository, workspaceSyncer,
                new gate.adapters.git.LocalGitCommitIdentityProvider(git, clock,
                        config.publishIdentity().name(), config.publishIdentity().email()),
                cloneBaseSyncer, this.topologyInitializer);
    }

    public GateConfig config() { return config; }
    public Clock clock() { return clock; }
    public ProcessRunner processRunner() { return processRunner; }
    public GitCli git() { return git; }
    /** @deprecated drivers should consume ports, not DataSource directly */
    @Deprecated public DataSource dataSource() { return dataSource; }
    GateRuntime.JdbcAccess jdbcAccess() { return new JdbcAccess(dataSource); }
    public static final class JdbcAccess { private final DataSource ds; JdbcAccess(DataSource ds){this.ds=ds;} DataSource ds(){return ds;} }
    public GateService gateService() { return gateService; }
    public TopologyInitializer topologyInitializer() { return topologyInitializer; }
    public PreflightChecker preflightChecker() { return preflightChecker; }
    public ProviderRepository providerRepository() { return providerRepository; }
    public TicketRepository ticketRepository() { return ticketRepository; }
    public PresubmitRepository presubmitRepository() { return presubmitRepository; }
    public AuditLog auditLog() { return auditLog; }
    public gate.ports.store.TicketStageChangeRepository ticketStageChangeRepository() { return ticketStageChangeRepository; }
    public ReviewResultRepository reviewResultRepository() { return reviewResultRepository; }
    public PublishIntentRepository publishIntentRepository() { return publishIntentRepository; }
    public BlobStore blobStore() { return blobStore; }
    public CredentialRepository credentials() { return credentials; }
    // Phase3 getters – expose PORT types, not implementation, to keep composition root sealed
    public JdbcGateTaskRepository gateTaskRepository() { return gateTaskRepository; }
    public gate.ports.task.TaskRegistry taskRegistry() { return gateTaskRepository; }
    public TaskClaimPort taskClaimPort() { return taskClaimPort; }
    public gate.ports.store.NonceStore nonceStore() { return nonceStore; }
    public AuthoritativeGitService authoritativeGitService() { return authoritativeGitService; }
    public EphemeralWorkspaceManager ephemeralWorkspaceManager() { return ephemeralWorkspaceManager; }
    public S3Store s3Store() { return s3Store; }
    public KmsService kmsService() { return kmsService; }
    public gate.ports.store.AgentConfigRepository agentConfigRepository() { return agentConfigRepository; }
    public gate.ports.store.SessionRepository sessionRepository() { return sessionRepository; }
    public gate.ports.store.ProjectRepository projectRepository() { return projectRepository; }
    public WorkspaceSyncer workspaceSyncer() { return workspaceSyncer; }
    public gate.ports.git.CloneBaseSyncer cloneBaseSyncer() { return cloneBaseSyncer; }

    /** Seeds a provider required by the review-result foreign key. Safe to call repeatedly. */
    public void seedProvider(String id, String name, String baseUrl, String apiKeyRef, String type) {
        if (providerRepository.find(id).isEmpty()) {
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    id, name, baseUrl, apiKeyRef, type, clock.now()), clock.now());
        }
    }

    /**
     * One-time legacy migration: bare values in {@code api_key_ref}（KMS 凭据流之前由旧设置页/CLI
     * 直接存入的明文 key）are encrypted in place as {@code kms:<ciphertext>}. Idempotent — kms:
     * and none/unconfigured rows are left untouched. Never logs the value.
     */
    private void migrateProviderCredentials() {
        for (ProviderRepository.ProviderRow p : providerRepository.findAll()) {
            String ref = p.apiKeyRef();
            if (!gate.adapters.engine.ApiKeyResolver.isLegacyPlaintext(ref)) {
                continue;
            }
            providerRepository.upsert(new ProviderRepository.ProviderRow(
                    p.id(), p.name(), p.baseUrl(),
                    "kms:" + kmsService.encrypt(ref.trim()), p.type(), p.createdAt()), clock.now());
        }
    }

    public void seedManualProvider() {
        seedProvider("manual", "manual (human reviewer)", "local://manual", "none", "manual");
    }

    public void seedCliDefaultProvider() {
        seedProvider("cli-default", "CLI default (internal)", "local://cli-default", "none", "cli-runtime");
    }
}
