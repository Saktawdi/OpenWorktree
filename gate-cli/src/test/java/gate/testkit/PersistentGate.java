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
import gate.domain.config.GateConfig;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.GatePolicy;
import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.infra.Clock;
import gate.ports.store.ProviderRepository;
import gate.ports.engine.PublishProbe;
import gate.ports.git.RefObserver;
import gate.ports.store.TicketRepository;
import gate.ports.git.TopologyInitializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Same object graph as {@link GateHarness}, but over a caller-supplied <em>persistent</em> root that
 * it neither wipes nor deletes, and with an injectable {@link PublishProbe}.
 *
 * <p>This is what makes the A5 crash-recovery test faithful: a child JVM drives a real
 * {@code publish} through the real service, and the probe hard-kills the JVM at a precise phase
 * ("after commit-tree", "mid-push"). The parent JVM then re-opens the same gate-home and runs the
 * real {@code reconcile}. Recovery is exercised across a genuine process boundary, and the publish
 * path is the production one — the probe adds only a no-op call site in production.
 */
public final class PersistentGate {

    private final GateConfig config;
    private final GateService gateService;
    private final TopologyInitializer topology;
    private final RefObserver refObserver;
    private final TicketRepository tickets;
    private final Clock clock = new SystemClock();

    public PersistentGate(Path root, String gitExecutable, PublishProbe probe) {
        Path gateHome = root.resolve("gate-home");
        try {
            Files.createDirectories(gateHome.resolve("approvals").resolve("consumed"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.config = new GateConfig(2, "a5",
                root.resolve("auth.git"), root.resolve("clones"), List.of("refs/heads/main"),
                gateHome, gateHome.resolve("approvals"), gateHome.resolve("gate.db"),
                gateHome.resolve("blobs"), gateHome.resolve("audit.jsonl"), gateHome.resolve("locks"),
                gateHome.resolve("idx"), new CommitIdentity("gate", "gate@localhost", "1700000000 +0000"),
                Policy.defaults(), null);

        ProcessRunnerImpl pr = new ProcessRunnerImpl(gateHome.resolve("proc"));
        GitCli git = new GitCli(pr, gitExecutable, Duration.ofSeconds(120));
        GitCliSnapshot snapshotCapture = new GitCliSnapshot(git, config.indexDir());
        GitCliPublisher commitPublisher = new GitCliPublisher(git, config.indexDir());
        this.refObserver = new GitCliRefObserver(git);
        FsApprovalStore approvalStore = new FsApprovalStore(config.approvalsDir());
        FileHookInstaller hook = new FileHookInstaller();
        this.topology = new GitCliTopologyInitializer(git, hook, config.targetRefWhitelist(),
                gateHome.resolve("tmp"));

        FsBlobStore blobStore = new FsBlobStore(config.blobRoot());
        HashChainAuditLog audit = new HashChainAuditLog(config.auditPath());
        FileChannelLockManager lock = new FileChannelLockManager(config.locksDir());
        ManualReviewEngineFactory engines = new ManualReviewEngineFactory(blobStore);
        GatePolicy policy = new GatePolicy();

        DataSource ds = SqliteDataSourceFactory.create(config.dbPath());
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate txt = new TransactionTemplate(new DataSourceTransactionManager(ds));
        SpringDbTransactionRunner tx = new SpringDbTransactionRunner(txt);
        this.tickets = new JdbcTicketRepository(jdbc);
        JdbcPresubmitRepository presubmits = new JdbcPresubmitRepository(jdbc);
        JdbcReviewResultRepository reviews = new JdbcReviewResultRepository(jdbc);
        JdbcPublishIntentRepository intents = new JdbcPublishIntentRepository(jdbc, config.authRepo());
        JdbcProviderRepository providers = new JdbcProviderRepository(jdbc);
        if (providers.find("manual").isEmpty()) {
            providers.upsert(new ProviderRepository.ProviderRow(
                    "manual", "manual", "local://manual", "none", "manual", clock.now()), clock.now());
        }

        this.gateService = new GateServiceImpl(config, snapshotCapture, commitPublisher, refObserver,
                approvalStore, engines, policy, tickets, presubmits, reviews, intents, blobStore, audit, lock, tx,
                clock, probe);
    }

    public void initTopology() {
        topology.initAuthRepo(RepoRef.of(config.authRepo()), config.primaryTargetRef(), config.approvalsDir());
    }

    public RepoRef createTicket(String ticketNo) {
        RepoRef clone = topology.createClone(RepoRef.of(config.authRepo()), config.primaryTargetRef(),
                config.clonesRoot().resolve(ticketNo));
        Instant now = clock.now();
        tickets.insert(new Ticket(ticketNo, ticketNo, config.primaryTargetRef(), clone.pathString(),
                null, null, "manual", "human", TicketStage.IN_PROGRESS, now, now));
        return clone;
    }

    public GateService service() {
        return gateService;
    }

    public GateConfig config() {
        return config;
    }

    public String authTip() {
        return refObserver.tip(RepoRef.of(config.authRepo()), config.primaryTargetRef()).map(ObjectId::hex).orElse("");
    }

    public long authCommitCount() {
        return refObserver.countCommits(RepoRef.of(config.authRepo()), config.primaryTargetRef());
    }
}
