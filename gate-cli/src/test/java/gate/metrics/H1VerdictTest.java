package gate.metrics;

import gate.application.H1Verdict;
import gate.application.MetricsService;
import gate.application.MetricsService.MetricRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H1 verdict three-tier boundary tests (执行文档 §4 P4, §15).
 *
 * <p>H1 is <b>designed to be refutable</b> — the verdict must not be adjusted or sample-filtered to
 * make H1 "look true" (执行文档 §4 P4 hard constraint).
 *
 * <p>Three tiers:
 * <ul>
 *   <li>ESTABLISHED — cost ratio < 20% AND first-pass rate > 60%</li>
 *   <li>PARTIAL — cost ratio 20%–40%</li>
 *   <li>REFUTED — cost ratio > 40% OR first-pass rate < 40%</li>
 * </ul>
 *
 * <p>Judgment only when {@code sampleCount >= 20}; fewer → INSUFFICIENT_SAMPLES.
 */
class H1VerdictTest {

    @Test
    void established_when_cost_ratio_below_20_and_first_pass_above_60() {
        List<MetricRecord> records = buildSamples(20, 0.15, 0.65);
        double costRatio = MetricsService.computeCostRatioMedian(records);
        double firstPass = MetricsService.computeFirstPassRate(records);
        assertEquals(H1Verdict.ESTABLISHED, classify(costRatio, firstPass, 20),
                "cost ratio 15% + first-pass 65% → ESTABLISHED");
    }

    @Test
    void partial_at_20_percent_boundary() {
        double costRatio = MetricsService.computeCostRatioMedian(
                buildSamples(20, 0.20, 0.65));
        double firstPass = MetricsService.computeFirstPassRate(buildSamples(20, 0.20, 0.65));
        assertEquals(H1Verdict.PARTIAL, classify(costRatio, firstPass, 20),
                "cost ratio exactly 20% → PARTIAL (boundary)");
    }

    @Test
    void partial_at_39_percent_boundary() {
        double costRatio = MetricsService.computeCostRatioMedian(
                buildSamples(20, 0.39, 0.65));
        double firstPass = MetricsService.computeFirstPassRate(buildSamples(20, 0.39, 0.65));
        assertEquals(H1Verdict.PARTIAL, classify(costRatio, firstPass, 20),
                "cost ratio 39% → PARTIAL (boundary)");
    }

    @Test
    void partial_at_40_percent_boundary() {
        double costRatio = MetricsService.computeCostRatioMedian(
                buildSamples(20, 0.40, 0.65));
        double firstPass = MetricsService.computeFirstPassRate(buildSamples(20, 0.40, 0.65));
        assertEquals(H1Verdict.PARTIAL, classify(costRatio, firstPass, 20),
                "cost ratio exactly 40% → PARTIAL (boundary: > 40% is REFUTED, so 40% is PARTIAL)");
    }

    @Test
    void refuted_just_above_40_percent() {
        double costRatio = MetricsService.computeCostRatioMedian(
                buildSamples(20, 0.401, 0.65));
        double firstPass = MetricsService.computeFirstPassRate(buildSamples(20, 0.401, 0.65));
        assertEquals(H1Verdict.REFUTED, classify(costRatio, firstPass, 20),
                "cost ratio just above 40% → REFUTED");
    }

    @Test
    void refuted_when_first_pass_below_40() {
        double costRatio = MetricsService.computeCostRatioMedian(
                buildSamples(20, 0.10, 0.35));
        double firstPass = MetricsService.computeFirstPassRate(buildSamples(20, 0.10, 0.35));
        assertEquals(H1Verdict.REFUTED, classify(costRatio, firstPass, 20),
                "first-pass rate 35% → REFUTED even with low cost ratio");
    }

    @Test
    void not_refuted_at_40_percent_first_pass() {
        double costRatio = MetricsService.computeCostRatioMedian(
                buildSamples(20, 0.10, 0.40));
        double firstPass = MetricsService.computeFirstPassRate(buildSamples(20, 0.10, 0.40));
        // first-pass rate == 40% is NOT < 40%, so it should not trigger REFUTED on that alone.
        assertNotEquals(H1Verdict.REFUTED, classify(costRatio, firstPass, 20),
                "first-pass rate exactly 40% should NOT trigger REFUTED (boundary: < 40%)");
    }

    // ------------------------------------------------------------------
    // Insufficient samples
    // ------------------------------------------------------------------

    @Test
    void insufficient_samples_below_20() {
        List<MetricRecord> records = buildSamples(19, 0.15, 0.65);
        int count = MetricsService.uniqueTicketCount(records);
        assertTrue(count < H1Verdict.MIN_SAMPLES_FOR_VERDICT,
                "19 samples < 20 threshold");
    }

    @Test
    void sufficient_at_20_samples() {
        List<MetricRecord> records = buildSamples(20, 0.15, 0.65);
        int count = MetricsService.uniqueTicketCount(records);
        assertTrue(count >= H1Verdict.MIN_SAMPLES_FOR_VERDICT,
                "20 samples >= 20 threshold");
    }

    // ------------------------------------------------------------------
    // Degraded basis (no token data)
    // ------------------------------------------------------------------

    @Test
    void degraded_basis_when_no_token_data() {
        List<MetricRecord> records = buildSamplesNoTokens(20, 0.65);
        double costRatio = MetricsService.computeCostRatioMedian(records);
        assertTrue(Double.isNaN(costRatio), "cost ratio must be NaN when no token data (degraded)");
    }

    @Test
    void degraded_basis_first_pass_below_40_still_refuted() {
        List<MetricRecord> records = buildSamplesNoTokens(20, 0.35);
        double firstPass = MetricsService.computeFirstPassRate(records);
        assertEquals(H1Verdict.REFUTED, classify(Double.NaN, firstPass, 20),
                "degraded basis but first-pass < 40% → REFUTED");
    }

    @Test
    void degraded_basis_first_pass_above_40_partial() {
        List<MetricRecord> records = buildSamplesNoTokens(20, 0.65);
        double firstPass = MetricsService.computeFirstPassRate(records);
        assertEquals(H1Verdict.PARTIAL, classify(Double.NaN, firstPass, 20),
                "degraded basis with first-pass > 40% → PARTIAL (cost ratio indeterminate)");
    }

    // ------------------------------------------------------------------
    // First-pass rate computation
    // ------------------------------------------------------------------

    @Test
    void first_pass_rate_counts_round_1_pass_tickets() {
        List<MetricRecord> records = new ArrayList<>();
        // 10 tickets with round 1 PASS (first-pass)
        for (int i = 0; i < 10; i++) {
            records.add(record("T-" + i, 1, "PASS", 100L, 1000L));
        }
        // 10 tickets with round 1 REJECT, round 2 PASS (not first-pass)
        for (int i = 10; i < 20; i++) {
            records.add(record("T-" + i, 1, "REJECT", 100L, 1000L));
            records.add(record("T-" + i, 2, "PASS", 100L, 1000L));
        }
        double rate = MetricsService.computeFirstPassRate(records);
        assertEquals(0.5, rate, 0.001, "10 of 20 tickets first-pass → 50%");
    }

    @Test
    void first_pass_rate_zero_when_no_round_1_pass() {
        List<MetricRecord> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(record("T-" + i, 1, "REJECT", 100L, 1000L));
            records.add(record("T-" + i, 2, "PASS", 100L, 1000L));
        }
        double rate = MetricsService.computeFirstPassRate(records);
        assertEquals(0.0, rate, 0.001, "no round-1 PASS → 0%");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Mirrors the MetricsService classification logic for unit testing without a DB. */
    private static String classify(double costRatioMedian, double firstPassRate, int sampleCount) {
        if (sampleCount < H1Verdict.MIN_SAMPLES_FOR_VERDICT) {
            return H1Verdict.INSUFFICIENT_SAMPLES;
        }
        if (!Double.isNaN(costRatioMedian)) {
            if (costRatioMedian > 0.40 || firstPassRate < 0.40) {
                return H1Verdict.REFUTED;
            } else if (costRatioMedian >= 0.20) {
                return H1Verdict.PARTIAL;
            } else if (firstPassRate > 0.60) {
                return H1Verdict.ESTABLISHED;
            } else {
                return H1Verdict.PARTIAL;
            }
        } else {
            if (firstPassRate < 0.40) {
                return H1Verdict.REFUTED;
            } else {
                return H1Verdict.PARTIAL;
            }
        }
    }

    /**
     * Builds N ticket records where each ticket has one review with the given cost ratio and first-pass
     * rate. The cost ratio is simulated by setting review tokens and exec tokens proportionally.
     *
     * @param n           number of tickets
     * @param costRatio   desired review cost ratio (review / (exec + review))
     * @param firstPassRate fraction of tickets whose round-1 verdict is PASS
     */
    private static List<MetricRecord> buildSamples(int n, double costRatio, double firstPassRate) {
        List<MetricRecord> records = new ArrayList<>();
        int firstPassCount = (int) Math.round(n * firstPassRate);
        for (int i = 0; i < n; i++) {
            String verdict = i < firstPassCount ? "PASS" : "REJECT";
            // review_total = costRatio * total; exec_total = (1 - costRatio) * total
            // Let total = 10000. review = costRatio * 10000, exec = (1 - costRatio) * 10000
            long total = 10000;
            long reviewTokens = (long) (costRatio * total);
            long execTokens = total - reviewTokens;
            records.add(new MetricRecord("T-" + i, 1, verdict, 500L, 50L,
                    reviewTokens, reviewTokens / 2, reviewTokens, "engine_json",
                    1000L, 900L, execTokens, "agent_cli"));
        }
        return records;
    }

    private static List<MetricRecord> buildSamplesNoTokens(int n, double firstPassRate) {
        List<MetricRecord> records = new ArrayList<>();
        int firstPassCount = (int) Math.round(n * firstPassRate);
        for (int i = 0; i < n; i++) {
            String verdict = i < firstPassCount ? "PASS" : "REJECT";
            records.add(new MetricRecord("T-" + i, 1, verdict, 500L, 50L,
                    null, null, null, "unavailable",
                    1000L, 900L, null, "unavailable"));
        }
        return records;
    }

    private static MetricRecord record(String ticketNo, int round, String verdict,
                                       Long reviewTokens, Long execTokens) {
        return new MetricRecord(ticketNo, round, verdict, 500L, 50L,
                reviewTokens, reviewTokens / 2, reviewTokens, "engine_json",
                1000L, 900L, execTokens, "agent_cli");
    }
}
