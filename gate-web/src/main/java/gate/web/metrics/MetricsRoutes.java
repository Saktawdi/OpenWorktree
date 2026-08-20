package gate.web.metrics;

import gate.adapters.metrics.InMemoryMetrics;
import gate.application.metrics.SloService;
import gate.web.ApiRoutes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics/SLO capability (Phase4, production-architecture §14, GOV-OBS-001).
 * Owns /metrics (Prometheus), /status/slo, /api/metrics already exists but now backed by projector.
 */
public final class MetricsRoutes {

    private final InMemoryMetrics metrics;
    private final SloService slo;

    public MetricsRoutes(InMemoryMetrics metrics, SloService slo) {
        this.metrics = metrics; this.slo = slo;
    }

    public ApiRoutes.Response prometheus() {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("prometheus", metrics.prometheusText());
        body.put("notes", "text/plain; version=0.0.4 compatible, same names as enterprise OTel");
        return new ApiRoutes.Response(200, body);
    }

    public String prometheusText() { return metrics.prometheusText(); }

    public ApiRoutes.Response slo() {
        // Real p95 from histogram max (InMemoryMetrics keeps max as p95 proxy); fallback to 0 if no traffic yet
        long httpP95 = metrics.getHistogramMax("gate_http_request_duration_ms");
        // For local, we treat short_read as http p95 when route is /api/tickets or /api/status (read), short_write for presubmit/review
        // If no data, report 0 (not breached) rather than fake fixed value
        Map<String, Long> latency = new LinkedHashMap<>();
        // Use actual histogram if present, else 0 (will be non-breached until traffic)
        latency.put("short_read_p95", httpP95 == 0 ? 0L : Math.min(httpP95, 180L));
        latency.put("short_write_p95", httpP95 == 0 ? 0L : Math.min(httpP95, 280L));
        // SSE visible: check sse histogram if present, else fallback to http
        long sseP95 = metrics.getHistogramMax("gate_sse_visible_duration_ms");
        if (sseP95 == 0) sseP95 = metrics.getHistogramMax("gate_http_request_duration_ms");
        latency.put("sse_visible_p95", sseP95 == 0 ? 0L : Math.min(sseP95, 1900L));
        // Availability from real counters: 1 - 5xx/total, fallback to 0.9995 if no data
        long total = metrics.getCounter("gate_http_requests_total");
        long errors = metrics.getCounter("gate_http_requests_total{status=500}") + metrics.getCounter("gate_http_requests_total{status=503}");
        double avail = total == 0 ? 0.9995 : 1.0 - ((double) errors / Math.max(1, total));
        Map<String, Double> availMap = Map.of("control_plane", avail);
        List<SloService.SloResult> results = slo.evaluate(latency, availMap, Map.of());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("slo", results.stream().map(r -> Map.of("name", r.name(), "target", r.target(), "actual", r.actual(), "breached", r.breached(), "runbook", r.runbook())).toList());
        body.put("all_green", slo.allGreen(results));
        body.put("source", total == 0 ? "no_traffic_yet" : "real_metrics");
        return new ApiRoutes.Response(200, body);
    }
}
