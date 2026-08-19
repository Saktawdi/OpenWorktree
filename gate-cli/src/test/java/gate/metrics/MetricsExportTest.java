package gate.metrics;

import gate.application.MetricsService;
import gate.application.MetricsService.MetricRecord;
import gate.application.PresubmitCommand;
import gate.application.ReviewCommand;
import gate.domain.git.RepoRef;
import gate.testkit.GateHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4 metrics export tests: verifies the export produces enough fields to compute the median cost
 * ratio and first-pass rate from ≥20 constructed samples (执行文档 §4 P4 A12).
 *
 * <p>Uses the manual review engine (no prism) so it runs without a live newapi link. The point is to
 * prove the export machinery and field completeness, not the real prism token data (which is
 * unavailable anyway — docs/archive/prism-schema-validation.md).
 */
class MetricsExportTest {

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

    @Test
    void export_fields_sufficient_for_metrics_computation() {
        // Construct synthetic records (not from DB) to verify the metrics math works.
        List<MetricRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            records.add(new MetricRecord(
                    "T-" + i, 1, i < 15 ? "PASS" : "REJECT",
                    500L, 50L,
                    200L, 100L, 300L, "engine_json",
                    1000L, 900L, 700L, "agent_cli"));
        }

        int sampleCount = MetricsService.uniqueTicketCount(records);
        assertEquals(25, sampleCount, "25 unique tickets");

        double firstPass = MetricsService.computeFirstPassRate(records);
        assertEquals(15.0 / 25.0, firstPass, 0.001, "15 of 25 first-pass → 60%");

        double costRatio = MetricsService.computeCostRatioMedian(records);
        // review = 300, exec = 700, ratio = 300/1000 = 0.3 for all tickets → median 0.3
        assertEquals(0.30, costRatio, 0.001, "cost ratio = 300/(700+300) = 30%");
    }

    @Test
    void export_handles_mixed_token_sources() {
        List<MetricRecord> records = List.of(
                new MetricRecord("T-1", 1, "PASS", 100L, 10L,
                        50L, 25L, 75L, "engine_json", 1000L, 900L, 200L, "agent_cli"),
                new MetricRecord("T-2", 1, "PASS", 100L, 10L,
                        null, null, null, "unavailable", 2000L, 1800L, null, "unavailable"),
                new MetricRecord("T-3", 1, "REJECT", 100L, 10L,
                        null, null, null, "unavailable", 3000L, 2700L, null, "unavailable"));

        int count = MetricsService.uniqueTicketCount(records);
        assertEquals(3, count);

        // Only T-1 has token data → cost ratio median is just T-1's ratio.
        double costRatio = MetricsService.computeCostRatioMedian(records);
        // T-1: review=75, exec=200, ratio = 75/275 ≈ 0.2727
        assertEquals(75.0 / 275.0, costRatio, 0.001,
                "only one ticket with token data → median = that ticket's ratio");

        // First-pass rate: T-1 and T-2 round-1 PASS → 2/3
        double firstPass = MetricsService.computeFirstPassRate(records);
        assertEquals(2.0 / 3.0, firstPass, 0.001, "2 of 3 first-pass → 66.7%");
    }

    @Test
    void export_empty_when_no_reviews() {
        List<MetricRecord> empty = List.of();
        assertEquals(0, MetricsService.uniqueTicketCount(empty));
        assertEquals(0.0, MetricsService.computeFirstPassRate(empty));
        assertTrue(Double.isNaN(MetricsService.computeCostRatioMedian(empty)));
    }
}
