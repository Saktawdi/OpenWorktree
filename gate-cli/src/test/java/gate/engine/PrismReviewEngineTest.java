package gate.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.blob.FsBlobStore;
import gate.adapters.engine.PrismReviewEngine;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.domain.snapshot.CaptureIntegrityReport;
import gate.domain.snapshot.Snapshot;
import gate.ports.store.BlobStore;
import gate.ports.infra.ProcessRunner;
import gate.ports.engine.ReviewEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A7: fail-closed for the prism adapter (架构落地执行文档 §4 P2, §8.4 items 4/6/9).
 *
 * <p>Every failure mode — timeout, crash, bad JSON, a vanished binary, configuration drift — must
 * become an {@link EngineFailure} <em>value</em>, never an exception, and GatePolicy turns each into a
 * reject. The adapter's {@code review()} body is the single mandated {@code catch (Throwable)}.
 *
 * <p>A fake {@link ProcessRunner} injects each outcome deterministically — no real prism call, since
 * the point is the fail-closed contract, not prism's accuracy (spike §4.4 #3).
 */
class PrismReviewEngineTest {

    private Path temp;
    private BlobStore blobStore;
    private FakeRunner runner;
    private PrismReviewEngine engine;
    private ReviewEngine.ReviewRequest request;

    @BeforeEach
    void setUp() throws Exception {
        temp = Files.createTempDirectory("prism-test-");
        blobStore = new FsBlobStore(temp.resolve("blobs"));
        runner = new FakeRunner();
        // acceptDegraded=false keeps the fail-closed default: N6 degraded flag stays true,
        // and GatePolicy still rejects a degraded report (production behaviour).
        engine = new PrismReviewEngine(runner, blobStore,
                "prism", Duration.ofSeconds(5),
                "newapi", "test-model",
                "https://newapi.sakta.top/v1", "fake-key", "prism 0.5.0", false);
        request = sampleRequest();
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(temp);
    }

    /** F1: timeout → EngineFailure(TIMEOUT), exit -1. */
    @Test
    void f1_timeoutBecomesFailure() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), -1, "", "killed",
                Duration.ZERO, true);
        ReviewEvidence e = engine.review(request);
        assertTrue(e instanceof EngineFailure, "timeout must be a failure value, not a report");
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.TIMEOUT, f.kind());
        assertTrue(f.detail().contains("exceeded"), "detail must explain the timeout");
    }

    /** F2: exit 4 (runtime mixed bucket) → EngineFailure(CRASH), classified detail. */
    @Test
    void f2_exit4RuntimeBecomesFailure() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 4, "",
                "Error: provider review: dial tcp: connection refused", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.CRASH, f.kind());
        assertEquals(4, f.exitCode());
        assertTrue(f.detail().contains("provider network"), "exit 4 must be classified from stderr");
    }

    /** exit 4 unclassifiable → still a CRASH reject (N5: fail-closed over retry precision). */
    @Test
    void f2b_exit4UnclassifiedStillRejects() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 4, "",
                "something totally unknown", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.CRASH, f.kind());
        assertTrue(f.detail().contains("unclassified"));
    }

    /** F3: exit 2 (usage / flag drift) → EngineFailure(CRASH), gate 22 territory. */
    @Test
    void f3_exit2UsageBecomesFailure() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 2, "",
                "Error: unknown flag: --foo", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.CRASH, f.kind());
        assertEquals(2, f.exitCode());
    }

    /** F4: exit 1 (configuration drift — --fail-on none forbids this) → reject. */
    @Test
    void f4_exit1ConfigDriftRejects() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 1, "{}", "", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.CRASH, f.kind());
        assertTrue(f.detail().contains("configuration drift"));
    }

    /** F5-ish: exit 3 (provider auth/config) → CRASH. */
    @Test
    void f5_exit3ProviderAuthRejects() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 3, "",
                "Error: 401 Unauthorized", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.CRASH, f.kind());
        assertEquals(3, f.exitCode());
    }

    /** Bad JSON on exit 0 → EngineFailure(UNPARSEABLE), never a pass. */
    @Test
    void badJsonBecomesUnparseable() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 0,
                "this is not json {{{", "", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.UNPARSEABLE, f.kind());
    }

    /** Happy path: valid JSON with high+medium findings parses into a report. */
    @Test
    void validJsonProducesReportWithMappedSeverities() {
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 0, SAMPLE_JSON, "",
                Duration.ofMillis(500), false);
        ReviewEvidence e = engine.review(request);
        assertTrue(e instanceof EngineReport, "valid JSON → report, not failure");
        EngineReport r = (EngineReport) e;
        assertEquals(2, r.findings().size());

        Finding high = r.findings().stream().filter(f -> f.severity() == Severity.BLOCKER).findFirst().orElseThrow();
        assertEquals("app.py", high.path());
        assertEquals(2, high.lineStart());
        assertEquals(2, high.lineEnd());
        assertEquals("high", high.rawSeverity());
        assertTrue(high.message().contains("SQL injection"), "title must be folded into message");

        Finding medium = r.findings().stream().filter(f -> f.severity() == Severity.WARNING).findFirst().orElseThrow();
        assertEquals("medium", medium.rawSeverity());

        // N6: prism has no coveredPaths field → adapter uses changedPaths + degraded=true.
        assertTrue(r.degraded(), "coveredPaths not proven by prism → degraded");
        assertTrue(r.coveredPaths().containsAll(request.snapshot().changedPaths()),
                "coveredPaths must be the full changedPaths set (N6)");
        assertEquals(0, r.exitCode());
    }

    /** Unknown severity → BLOCKER + degraded (§5.3 constraint 3). */
    @Test
    void unknownSeverityMapsToBlockerAndDegrades() {
        String json = SAMPLE_JSON.replace("\"high\"", "\"critical\"");
        runner.next = new ProcessRunner.ProcRun(List.of("prism"), 0, json, "", Duration.ZERO, false);
        ReviewEvidence e = engine.review(request);
        EngineReport r = (EngineReport) e;
        Finding f = r.findings().get(0);
        assertEquals(Severity.BLOCKER, f.severity(), "unknown word → BLOCKER");
        assertEquals("critical", f.rawSeverity(), "raw preserved for audit");
        assertTrue(r.degraded(), "unknown severity forces degraded");
    }

    /** The adapter's review() never throws, even when the runner explodes. */
    @Test
    void runnerExceptionBecomesFailure() {
        runner.throwNext = new RuntimeException("process table full");
        ReviewEvidence e = engine.review(request);
        EngineFailure f = (EngineFailure) e;
        assertEquals(EngineFailure.FailureKind.CRASH, f.kind());
        assertTrue(f.detail().contains("process table full"));
    }

    /** --fail-on none is fixed in argv; describe() carries the fingerprint. */
    @Test
    void argvContainsFailOnNone() {
        engine.review(request); // exercises buildArgv via the runner
        List<String> argv = runner.lastArgv;
        int failOnIdx = argv.indexOf("--fail-on");
        assertTrue(failOnIdx >= 0 && failOnIdx + 1 < argv.size(), "--fail-on must be present");
        assertEquals("none", argv.get(failOnIdx + 1), "verdict authority stays in GatePolicy");
        assertFalse(argv.stream().anyMatch(a -> a.contains("fake-key")),
                "API key must never appear in argv (§6.1)");
    }

    @Test
    void describeCarriesProviderAndModel() {
        EngineDescriptor d = engine.describe();
        assertEquals("prism", d.engineId());
        assertEquals("newapi", d.providerId());
        assertEquals("test-model", d.modelName());
        assertEquals("prism 0.5.0", d.engineVersion());
        assertFalse(d.argvFingerprint().isBlank());
    }

    private ReviewEngine.ReviewRequest sampleRequest() {
        ObjectId tree = ObjectId.of("1111111111111111111111111111111111111111");
        ObjectId base = ObjectId.of("2222222222222222222222222222222222222222");
        ObjectId baseTree = ObjectId.of("3333333333333333333333333333333333333333");
        ObjectId dangling = ObjectId.of("4444444444444444444444444444444444444444");
        Snapshot snapshot = new Snapshot(tree, base, baseTree, "refs/heads/main",
                List.of("app.py", "config.py"), "diff body", CaptureIntegrityReport.clean());
        RepoRef clone = RepoRef.of(temp.resolve("clone"));
        return new ReviewEngine.ReviewRequest(clone, "T-1", 1, snapshot, dangling);
    }

    /** A ProcessRunner that returns whatever the test staged, recording the argv for assertions. */
    static final class FakeRunner implements ProcessRunner {
        ProcessRunner.ProcRun next;
        RuntimeException throwNext;
        List<String> lastArgv;

        @Override
        public ProcRun run(List<String> argv, Path cwd, Map<String, String> env, Duration timeout) {
            lastArgv = new ArrayList<>(argv);
            // Assert the key is in env, not argv.
            if (throwNext != null) {
                throw throwNext;
            }
            return next;
        }
    }

    /** Real prism output (docs/archive/prism-schema-validation.md), trimmed to fields the parser reads. */
    private static final String SAMPLE_JSON = """
            {
              "tool": "prism",
              "version": "1.0",
              "summary": { "counts": { "low": 0, "medium": 1, "high": 1 }, "highestSeverity": "high" },
              "findings": [
                {
                  "id": "c8c11ece8f090cdf",
                  "severity": "high",
                  "category": "security",
                  "title": "SQL injection in login query",
                  "message": "The `user` parameter is concatenated directly into a SQL query.",
                  "suggestion": "Use parameterized queries.",
                  "confidence": 1,
                  "locations": [ { "path": "app.py", "lines": { "start": 2, "end": 2 } } ],
                  "tags": ["sql-injection"]
                },
                {
                  "id": "366a2e6aa7c396df",
                  "severity": "medium",
                  "category": "correctness",
                  "title": "Login function does not verify password",
                  "message": "No password check.",
                  "suggestion": "Add a password parameter.",
                  "confidence": 0.6,
                  "locations": [ { "path": "app.py", "lines": { "start": 1, "end": 3 } } ],
                  "tags": ["auth"]
                }
              ],
              "_provenance": [ { "provider": "openai", "model": "test-model" } ]
            }
            """;
}
