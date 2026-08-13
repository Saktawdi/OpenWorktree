package gate.metrics;

import gate.adapters.engine.PrismReviewEngine;
import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineReport;
import gate.domain.review.ReviewEvidence;
import gate.ports.BlobStore;
import gate.ports.CostHint;
import gate.ports.ProcessRunner;
import gate.ports.ReviewEngine;
import gate.testkit.GateHarness;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4 cost extraction tests: verifies token source annotation and the three possible source values
 * (engine_json / gateway_usage / unavailable), plus the honest degradation path
 * (执行文档 §4 P4, §15).
 *
 * <p>prism's JSON output (confirmed in doc/p2-schema-核对.md §3) has a {@code timing} object with
 * {@code totalMs} and {@code llmMs} but <b>no usage/token field</b>. So the token source is always
 * {@code "unavailable"} for prism, but timing data is extracted as the degraded basis. The manual
 * review engine returns {@link CostHint#EMPTY} (no telemetry at all).
 */
class CostExtractionTest {

    private GateHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GateHarness();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    // ------------------------------------------------------------------
    // Manual review engine: no cost data at all
    // ------------------------------------------------------------------

    @Test
    void manual_engine_returns_empty_cost() {
        ReviewEngine manualEngine = new gate.adapters.engine.ManualReviewEngine(harness.blobStore(), true, "test");
        // Build a minimal evidence to pass to extractCost — the manual engine ignores it.
        EngineDescriptor desc = new EngineDescriptor("manual", "0", "", "manual", "human");
        ReviewEvidence evidence = new EngineReport(desc, "tree", List.of(), java.util.Set.of(),
                false, new BlobRef("raw/empty.json", 0, "0".repeat(64)), 0, java.time.Duration.ZERO);
        java.util.Optional<CostHint> cost = manualEngine.extractCost(evidence);
        assertTrue(cost.isEmpty(), "manual engine should return empty cost (no extractCost override)");
    }

    // ------------------------------------------------------------------
    // Prism engine: extracts timing, no token data
    // ------------------------------------------------------------------

    @Test
    void prism_engine_extracts_timing_but_marks_tokens_unavailable() {
        // Simulate a prism JSON output with timing but no usage.
        String prismJson = "{\"tool\":\"prism\",\"version\":\"1.0\",\"timing\":{\"gitMs\":10,\"llmMs\":32936,\"totalMs\":32981},\"findings\":[]}";
        BlobStore blobStore = harness.blobStore();
        BlobRef rawRef = blobStore.put(prismJson.getBytes(StandardCharsets.UTF_8),
                "raw/test/prism-timing.json");

        EngineDescriptor desc = new EngineDescriptor("prism", "0.5.0", "fingerprint",
                "newapi", "DeepSeek-V4");
        ReviewEvidence evidence = new EngineReport(desc, "tree", List.of(), java.util.Set.of(),
                false, rawRef, 0, java.time.Duration.ZERO);

        // Use the real PrismReviewEngine just for extractCost — its review() is irrelevant here.
        PrismReviewEngine engine = new PrismReviewEngine(
                new FakeRunner(), blobStore, "prism", java.time.Duration.ofSeconds(60),
                "newapi", "DeepSeek-V4", "https://newapi.sakta.top/v1", "fake-key", "0.5.0");

        java.util.Optional<CostHint> costOpt = engine.extractCost(evidence);
        assertTrue(costOpt.isPresent(), "prism engine should extract cost hint");
        CostHint cost = costOpt.get();
        assertEquals("unavailable", cost.tokenSource(),
                "prism JSON has no usage → token source = unavailable");
        assertNull(cost.totalTokens(), "no token data");
        assertEquals(32981L, cost.reviewWallMs(), "totalMs from prism timing");
        assertEquals(32936L, cost.llmWallMs(), "llmMs from prism timing");
        assertFalse(cost.hasTokenData(), "hasTokenData must be false for prism");
    }

    @Test
    void prism_engine_unparseable_json_returns_empty_cost() {
        BlobStore blobStore = harness.blobStore();
        BlobRef rawRef = blobStore.put("not valid json".getBytes(StandardCharsets.UTF_8),
                "raw/test/bad.json");

        EngineDescriptor desc = new EngineDescriptor("prism", "0.5.0", "fingerprint",
                "newapi", "DeepSeek-V4");
        ReviewEvidence evidence = new EngineReport(desc, "tree", List.of(), java.util.Set.of(),
                false, rawRef, 0, java.time.Duration.ZERO);

        PrismReviewEngine engine = new PrismReviewEngine(
                new FakeRunner(), blobStore, "prism", java.time.Duration.ofSeconds(60),
                "newapi", "DeepSeek-V4", "https://newapi.sakta.top/v1", "fake-key", "0.5.0");

        java.util.Optional<CostHint> costOpt = engine.extractCost(evidence);
        assertTrue(costOpt.isPresent(), "should return EMPTY rather than throwing");
        assertEquals(CostHint.EMPTY.tokenSource(), costOpt.get().tokenSource(),
                "unparseable JSON → unavailable cost");
    }

    @Test
    void prism_engine_missing_timing_returns_empty_cost() {
        String prismJson = "{\"tool\":\"prism\",\"version\":\"1.0\",\"findings\":[]}";
        BlobStore blobStore = harness.blobStore();
        BlobRef rawRef = blobStore.put(prismJson.getBytes(StandardCharsets.UTF_8),
                "raw/test/no-timing.json");

        EngineDescriptor desc = new EngineDescriptor("prism", "0.5.0", "fingerprint",
                "newapi", "DeepSeek-V4");
        ReviewEvidence evidence = new EngineReport(desc, "tree", List.of(), java.util.Set.of(),
                false, rawRef, 0, java.time.Duration.ZERO);

        PrismReviewEngine engine = new PrismReviewEngine(
                new FakeRunner(), blobStore, "prism", java.time.Duration.ofSeconds(60),
                "newapi", "DeepSeek-V4", "https://newapi.sakta.top/v1", "fake-key", "0.5.0");

        java.util.Optional<CostHint> costOpt = engine.extractCost(evidence);
        assertTrue(costOpt.isPresent());
        assertNull(costOpt.get().reviewWallMs(), "no timing → null wall-ms");
        assertNull(costOpt.get().llmWallMs(), "no timing → null llm-ms");
    }

    @Test
    void engine_failure_evidence_returns_empty_cost() {
        EngineDescriptor desc = new EngineDescriptor("prism", "0.5.0", "fingerprint",
                "newapi", "DeepSeek-V4");
        ReviewEvidence failure = new gate.domain.review.EngineFailure(desc,
                gate.domain.review.EngineFailure.FailureKind.CRASH, "test crash", -1);

        PrismReviewEngine engine = new PrismReviewEngine(
                new FakeRunner(), harness.blobStore(), "prism", java.time.Duration.ofSeconds(60),
                "newapi", "DeepSeek-V4", "https://newapi.sakta.top/v1", "fake-key", "0.5.0");

        java.util.Optional<CostHint> costOpt = engine.extractCost(failure);
        assertTrue(costOpt.isPresent());
        assertEquals(CostHint.EMPTY.tokenSource(), costOpt.get().tokenSource(),
                "engine failure → unavailable cost");
    }

    // --- fake process runner (not actually used — extractCost reads from blobStore) ---
    private static final class FakeRunner implements ProcessRunner {
        @Override
        public ProcRun run(List<String> argv, java.nio.file.Path cwd,
                           Map<String, String> env, java.time.Duration timeout) {
            return new ProcRun(List.of("fake"), 0, "", "", java.time.Duration.ZERO, false);
        }
    }
}
