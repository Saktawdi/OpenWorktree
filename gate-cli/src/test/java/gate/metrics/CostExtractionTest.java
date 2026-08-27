package gate.metrics;

import gate.adapters.engine.BuiltinReviewEngine;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineReport;
import gate.domain.review.ReviewEvidence;
import gate.ports.session.CostHint;
import gate.ports.engine.ReviewEngine;
import gate.testkit.GateHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4 cost extraction tests: verifies token source annotation and the possible source values
 * (stream_usage / unavailable), plus the honest degradation path（执行文档 §4 P4, §15）。
 *
 * <p>prism 双轨已移除：原「prism JSON 只有 timing、token 恒 unavailable」的用例随
 * {@code PrismReviewEngine} 删除。gate-engine 的 usage 随 {@link EngineReport} 闭环传递，
 * {@code extractCost} 只读报告字段（引擎实例零状态）——本测试直接构造证据验证该契约，
 * 不需要起 HTTP stub。
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
        EngineDescriptor desc = new EngineDescriptor("manual", "0", "", "manual", "human");
        ReviewEvidence evidence = new EngineReport(desc, "tree", List.of(), java.util.Set.of(),
                false, ref(), 0, java.time.Duration.ZERO);
        assertTrue(manualEngine.extractCost(evidence).isEmpty(),
                "manual engine should return empty cost (no extractCost override)");
    }

    // ------------------------------------------------------------------
    // gate-engine: usage 随报告传递；extractCost 只读证据字段
    // ------------------------------------------------------------------

    @Test
    void gate_engine_with_usage_reports_stream_usage_source() {
        ReviewEvidence evidence = report(2400L, 5792L, 8192L);

        CostHint cost = builtin().extractCost(evidence).orElseThrow();

        assertEquals("stream_usage", cost.tokenSource(), "usage 在场 → tokenSource=stream_usage");
        assertEquals(2400L, cost.promptTokens());
        assertEquals(5792L, cost.completionTokens());
        assertEquals(8192L, cost.totalTokens());
        assertTrue(cost.hasTokenData());
        assertEquals(32981L, cost.reviewWallMs(), "reviewWallMs 取自报告 duration");
        assertNull(cost.llmWallMs(), "内建引擎无 LLM-only 计时，诚实置空");
    }

    @Test
    void gate_engine_without_usage_degrades_to_unavailable() {
        ReviewEvidence evidence = report(null, null, null);

        CostHint cost = builtin().extractCost(evidence).orElseThrow();

        assertEquals("unavailable", cost.tokenSource(), "无 usage 帧 → 诚实降级 unavailable");
        assertNull(cost.promptTokens());
        assertNull(cost.totalTokens());
        assertFalse(cost.hasTokenData());
        assertEquals(32981L, cost.reviewWallMs(), "墙钟仍是可用降级基准");
    }

    @Test
    void engine_failure_evidence_returns_empty_cost() {
        EngineDescriptor desc = new EngineDescriptor("gate-engine", "v1", "builtin:chat.completions",
                "temp", "test-model");
        ReviewEvidence failure = new gate.domain.review.EngineFailure(desc,
                gate.domain.review.EngineFailure.FailureKind.TIMEOUT, "total timeout after 600s", -1);

        CostHint cost = builtin().extractCost(failure).orElseThrow();

        assertEquals(CostHint.EMPTY.tokenSource(), cost.tokenSource(),
                "engine failure → empty cost (bypass 数据不阻塞发布)");
    }

    /** extractCost 从不动 blobStore：共享的 harness 实例即足够。 */
    private BuiltinReviewEngine builtin() {
        return new BuiltinReviewEngine(harness.blobStore(),
                java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(90),
                "temp", "test-model", "http://127.0.0.1:1/v1", "fake-key", null);
    }

    private static EngineReport report(Long pt, Long ct, Long tt) {
        EngineDescriptor desc = new EngineDescriptor("gate-engine", "v1",
                "builtin:chat.completions", "temp", "test-model");
        return new EngineReport(desc, "tree", List.of(), java.util.Set.of(),
                false, ref(), 0, java.time.Duration.ofMillis(32981), pt, ct, tt);
    }

    private static gate.domain.blob.BlobRef ref() {
        return new gate.domain.blob.BlobRef("raw/test/builtin.json", 0, "0".repeat(64));
    }
}
