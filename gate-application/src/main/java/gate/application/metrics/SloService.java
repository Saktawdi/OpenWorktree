package gate.application.metrics;

import gate.domain.metrics.SloTarget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SLO evaluation against targets in §14. Used by /status/slo, verify-capacity, and alerts.
 */
public final class SloService {

    public record SloResult(String name, String target, String actual, boolean breached, String runbook) {}

    public List<SloResult> evaluate(Map<String, Long> latencyP95Ms, Map<String, Double> availability, Map<String, Long> queueDepth) {
        List<SloResult> out = new ArrayList<>();
        long shortReadP95 = latencyP95Ms.getOrDefault("short_read_p95", 0L);
        out.add(new SloResult("short_read_p95", SloTarget.SHORT_READ_P95.toMillis()+"ms", shortReadP95+"ms",
                shortReadP95 > SloTarget.SHORT_READ_P95.toMillis(), "runbook/slo.md#short_read"));
        long shortWriteP95 = latencyP95Ms.getOrDefault("short_write_p95", 0L);
        out.add(new SloResult("short_write_p95", SloTarget.SHORT_WRITE_P95.toMillis()+"ms", shortWriteP95+"ms",
                shortWriteP95 > SloTarget.SHORT_WRITE_P95.toMillis(), "runbook/slo.md#short_write"));
        long sseVisible = latencyP95Ms.getOrDefault("sse_visible_p95", 0L);
        out.add(new SloResult("sse_visible_p95", SloTarget.SSE_VISIBLE_P95.toMillis()+"ms", sseVisible+"ms",
                sseVisible > SloTarget.SSE_VISIBLE_P95.toMillis(), "runbook/sse.md"));
        double avail = availability.getOrDefault("control_plane", 1.0);
        out.add(new SloResult("availability", ">=99.9%", String.format("%.4f", avail),
                avail < SloTarget.CONTROL_PLANE_AVAILABILITY, "runbook/availability.md"));
        return out;
    }

    public boolean allGreen(List<SloResult> results) { return results.stream().noneMatch(SloResult::breached); }
}
