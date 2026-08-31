package gate.testkit;

import gate.adapters.approval.FsApprovalStore;
import gate.adapters.audit.HashChainAuditLog;
import gate.adapters.blob.FsBlobStore;
import gate.adapters.clock.SystemClock;
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
import gate.adapters.store.JdbcPublishIntentRepository;
import gate.adapters.store.JdbcReviewResultRepository;
import gate.adapters.store.JdbcTicketRepository;
import gate.adapters.store.SpringDbTransactionRunner;
import gate.adapters.store.SqliteDataSourceFactory;
import gate.application.GateService;
import gate.application.GateServiceImpl;
import gate.domain.config.GateConfig;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.GatePolicy;
import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.store.ApprovalStore;
import gate.ports.store.AuditLog;
import gate.ports.store.BlobStore;
import gate.ports.infra.Clock;
import gate.ports.git.CommitPublisher;
import gate.ports.infra.DbTransactionRunner;
import gate.ports.git.HookInstaller;
import gate.ports.infra.LockManager;
import gate.ports.engine.PreflightChecker;
import gate.ports.store.PresubmitRepository;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProviderRepository;
import gate.ports.store.PublishIntentRepository;
import gate.ports.git.RefObserver;
import gate.ports.engine.ReviewEngineFactory;
import gate.ports.store.ReviewResultRepository;
import gate.ports.git.SnapshotCapture;
import gate.ports.store.TicketRepository;
import gate.ports.git.TopologyInitializer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds a live gate over a temporary directory, wired by hand with the real adapters and the real
 * git binary (fixture layer, 架构落地执行文档 §11.1).
 *
 * <p>No Spring: the harness constructs the same graph {@code GateComponents} builds, which
 * doubles as proof that the domain/application layers carry no Spring dependency. And <b>no JGit</b>:
 * every repo is built with the real {@code git} binary via {@link GitCli}, because the adversary uses
 * real git and a JGit-built fixture would test different tree semantics (ADR-1, §11.1).
 *
 * <p>All git invocations run with {@code GIT_CONFIG_NOSYSTEM=1} and repo-scoped identity, so tests
 * never depend on, or mutate, the developer's global git config.
 */
public final class GateHarness implements AutoCloseable {

    private final Path root;
    private final GateConfig config;
    private final GitCli git;
    private final GateService gateService;
    private final TopologyInitializer topologyInitializer;
    private final PreflightChecker preflightChecker;
    private final RefObserver refObserver;
    private final CommitPublisher commitPublisher;
    private final ApprovalStore approvalStore;
    private final HookInstaller hookInstaller;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final PublishIntentRepository intents;
    private final ReviewResultRepository reviewResults;
    private final BlobStore blobStore;
    private final HashChainAuditLog auditLog;
    private final Clock clock;
    private final ObjectId baseCommit;
    private final String gitExe;
    private final JdbcCredentialRepository credentials;
    private final ProviderRepository providers;

    public GateHarness() {
        this("git");
    }

    public GateHarness(String gitExecutable) {
        this.gitExe = gitExecutable;
        try {
            this.root = Files.createTempDirectory("gate-test-");
        } catch (IOException e) {
            throw new RuntimeException("cannot create temp dir", e);
        }
        Path gateHome = root.resolve("gate-home");
        try {
            Files.createDirectories(gateHome.resolve("approvals").resolve("consumed"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.config = new GateConfig(
                2, "test",
                root.resolve("auth.git"),
                root.resolve("clones"),
                List.of("refs/heads/main"),
                gateHome,
                gateHome.resolve("approvals"),
                gateHome.resolve("gate.db"),
                gateHome.resolve("blobs"),
                gateHome.resolve("audit.jsonl"),
                gateHome.resolve("locks"),
                gateHome.resolve("idx"),
                new CommitIdentity("gate", "gate@localhost", "1700000000 +0000"),
                Policy.defaults(),
                null);

        ProcessRunner processRunner = new ProcessRunnerImpl(gateHome.resolve("proc"));
        this.git = new GitCli(processRunner, gitExecutable, Duration.ofSeconds(120));

        SnapshotCapture snapshotCapture = new GitCliSnapshot(git, config.indexDir());
        this.commitPublisher = new GitCliPublisher(git, config.indexDir());
        this.refObserver = new GitCliRefObserver(git);
        this.approvalStore = new FsApprovalStore(config.approvalsDir());
        this.hookInstaller = new FileHookInstaller();
        this.topologyInitializer = new GitCliTopologyInitializer(git, hookInstaller,
                config.targetRefWhitelist(), gateHome.resolve("tmp"));
        this.preflightChecker = new DefaultPreflightChecker(config, git, hookInstaller, processRunner);

        BlobStore blobStore = new FsBlobStore(config.blobRoot());
        this.blobStore = blobStore;
        this.auditLog = new HashChainAuditLog(config.auditPath());
        LockManager lockManager = new FileChannelLockManager(config.locksDir());
        ReviewEngineFactory reviewEngineFactory = new ManualReviewEngineFactory(blobStore);
        GatePolicy gatePolicy = new GatePolicy();

        DataSource dataSource = SqliteDataSourceFactory.create(config.dbPath());
        SqliteDataSourceFactory.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DbTransactionRunner txRunner = new SpringDbTransactionRunner(txTemplate);

        this.tickets = new JdbcTicketRepository(jdbc);
        PresubmitRepository presubmits = new JdbcPresubmitRepository(jdbc);
        this.presubmits = presubmits;
        ReviewResultRepository reviewResults = new JdbcReviewResultRepository(jdbc);
        this.reviewResults = reviewResults;
        this.intents = new JdbcPublishIntentRepository(jdbc, config.authRepo());
        ProviderRepository providers = new JdbcProviderRepository(jdbc);
        this.providers = providers;
        this.clock = new SystemClock();
        this.credentials = new JdbcCredentialRepository(jdbc);

        // Seed the manual provider so review_result.provider_id (NOT NULL) is satisfied.
        providers.upsert(new ProviderRepository.ProviderRow(
                "manual", "manual", "local://manual", "none", "manual", clock.now()), clock.now());

        this.gateService = new GateServiceImpl(config, snapshotCapture, commitPublisher, refObserver,
                approvalStore, reviewEngineFactory, gatePolicy, tickets, presubmits, reviewResults, intents,
                blobStore, auditLog, lockManager, txRunner, clock,
                gate.ports.engine.PublishProbe.NOOP, null, null, null, null, null,
                this.topologyInitializer);

        // Build the topology: bare auth repo + seeded base + installed hook.
        this.baseCommit = topologyInitializer.initAuthRepo(RepoRef.of(config.authRepo()),
                config.primaryTargetRef(), config.approvalsDir()).baseCommit();
    }

    /** Registers a ticket and creates its independent clone. Returns the clone path. */
    public RepoRef createTicket(String ticketNo) {
        RepoRef clone = topologyInitializer.createClone(RepoRef.of(config.authRepo()),
                config.primaryTargetRef(), config.clonesRoot().resolve(ticketNo));
        Instant now = clock.now();
        tickets.insert(new Ticket(ticketNo, ticketNo, config.primaryTargetRef(), clone.pathString(),
                null, null, "manual", "human", TicketStage.IN_PROGRESS, now, now));
        return clone;
    }

    /** Current state of a registered ticket. */
    public Ticket ticket(String ticketNo) {
        return tickets.find(ticketNo).orElseThrow(() -> new IllegalStateException("no such ticket: " + ticketNo));
    }

    /** Writes a file into a clone's worktree. */
    public void writeFile(RepoRef clone, String relPath, String content) {
        try {
            Path target = clone.path().resolve(relPath);
            Files.createDirectories(target.getParent() == null ? clone.path() : target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Current tip of the authoritative target branch, or {@code ""} if unborn. */
    public String authTip() {
        return refObserver.tip(RepoRef.of(config.authRepo()), config.primaryTargetRef())
                .map(ObjectId::hex).orElse("");
    }

    public long authCommitCount() {
        return refObserver.countCommits(RepoRef.of(config.authRepo()), config.primaryTargetRef());
    }

    /** Runs raw git in a repo, returning the outcome (used by bypass tests to construct attacks). */
    public ProcessRunner.ProcRun git(RepoRef repo, String... args) {
        return git.run(repo, args);
    }

    public ProcessRunner.ProcRun gitIn(Path cwd, Map<String, String> env, String... args) {
        return git.run(cwd, env, args);
    }

    public GitCli gitCli() {
        return git;
    }

    public GateService service() {
        return gateService;
    }

    public GateConfig config() {
        return config;
    }

    public TopologyInitializer topology() {
        return topologyInitializer;
    }

    public PreflightChecker preflight() {
        return preflightChecker;
    }

    public RefObserver refObserver() {
        return refObserver;
    }

    public CommitPublisher commitPublisher() {
        return commitPublisher;
    }

    public ApprovalStore approvalStore() {
        return approvalStore;
    }

    public HookInstaller hookInstaller() {
        return hookInstaller;
    }

    public PublishIntentRepository intents() {
        return intents;
    }

    public PresubmitRepository presubmits() {
        return presubmits;
    }

    public ReviewResultRepository reviewResults() {
        return reviewResults;
    }

    public BlobStore blobStore() {
        return blobStore;
    }

    public JdbcCredentialRepository credentials() {
        return credentials;
    }

    public ProviderRepository providerRepository() {
        return providers;
    }

    public HashChainAuditLog auditLog() {
        return auditLog;
    }

    public ObjectId baseCommit() {
        return baseCommit;
    }

    public Path root() {
        return root;
    }

    public RepoRef authRepo() {
        return RepoRef.of(config.authRepo());
    }

    public String targetRef() {
        return config.primaryTargetRef();
    }

    /** Fixed identity env for building commits directly in tests (deterministic SHAs). */
    public Map<String, String> identityEnv() {
        return Map.of(
                "GIT_AUTHOR_NAME", "gate", "GIT_AUTHOR_EMAIL", "gate@localhost", "GIT_AUTHOR_DATE", "1700000000 +0000",
                "GIT_COMMITTER_NAME", "gate", "GIT_COMMITTER_EMAIL", "gate@localhost",
                "GIT_COMMITTER_DATE", "1700000000 +0000");
    }

    @Override
    public void close() {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }
}
